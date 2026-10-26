"""LogisticaWB — зарплата водителей по данным WB Логистики.

Запуск:  python app.py   (или двойной щелчок по start.bat)
Нужен только Python 3.9+, сторонние пакеты на Windows не нужны.

При первом запуске сайт попросит создать администратора. Администратор
добавляет кабинеты (ИП) с ключами WB и сотрудников; водитель входит под своим
логином и видит только свои путевые листы и отчёты.
"""
import hashlib
import hmac
import json
import os
import re
import secrets
import sys
import threading
import time
import webbrowser
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from holds import fetch_balance, fetch_pretensions
from remains import RemainsError, fetch_remains, parse_remains
from core import (NO_TYPE, KeyStore, TtlCache, get_json, post_json, WaysheetCache, WbError, add_days, build_report, day_of,
                  extract_token, fines_summary, key_info, parse_percent, trip_view, valid_date)

BASE_DIR = Path(__file__).resolve().parent
PUBLIC_DIR = BASE_DIR / "public"
DATA_DIR = Path(os.environ.get("DATA_DIR") or BASE_DIR / "data").resolve()
PORT = int(os.environ.get("PORT") or 3000)
HOST = os.environ.get("HOST") or "127.0.0.1"
MAX_PERIOD_DAYS = 93
SESSION_TTL = 30 * 86400
COOKIE = "sid"


# ---------------------------------------------------------------- хранилище

def hash_password(password, salt=None):
    salt = salt or secrets.token_hex(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode(), bytes.fromhex(salt), 200_000).hex()
    return salt, digest


def check_password(user, password):
    _, digest = hash_password(password, user["salt"])
    return hmac.compare_digest(digest, user["hash"])


def norm_name(s):
    return " ".join(str(s or "").replace("ё", "е").replace("Ё", "Е").split()).casefold()


class Db:
    """Пользователи, кабинеты, настройки и сессии в data/db.json."""

    def __init__(self, data_dir):
        self.dir = Path(data_dir)
        self.file = self.dir / "db.json"
        self.lock = threading.RLock()
        self.data = {"users": [], "cabinets": [], "settings": {"percent": 0}, "sessions": {}}
        if self.file.exists():
            self.data.update(json.loads(self.file.read_text(encoding="utf-8")))
        self.keys = {}

    def save(self):
        with self.lock:
            self.dir.mkdir(parents=True, exist_ok=True)
            tmp = self.file.with_suffix(".tmp")
            tmp.write_text(json.dumps(self.data, ensure_ascii=False, indent=2), encoding="utf-8")
            if sys.platform != "win32":
                os.chmod(tmp, 0o600)
            os.replace(tmp, self.file)

    def new_id(self):
        return secrets.token_hex(4)

    # --- пользователи
    def users(self):
        return self.data["users"]

    def user(self, uid):
        return next((u for u in self.users() if u["id"] == uid), None)

    def user_by_login(self, login):
        return next((u for u in self.users() if u["login"].casefold() == str(login).strip().casefold()), None)

    # --- кабинеты
    def cabinets(self):
        return self.data["cabinets"]

    def cabinet(self, cid):
        return next((c for c in self.cabinets() if c["id"] == cid), None)

    # kind: "main" — ключ из адреса ol.wildberries.ru (путевые);
    #       "svc"  — ключ остатков и удержаний, если WB требует для них другой.
    def key_store(self, cid, kind="main"):
        if (cid, kind) not in self.keys:
            name = f"wb-key-{cid}.enc" if kind == "main" else f"wb-svc-{cid}.enc"
            self.keys[(cid, kind)] = KeyStore(self.dir, filename=name)
        return self.keys[(cid, kind)]

    def token(self, cid, kind="main"):
        try:
            return self.key_store(cid, kind).load()
        except Exception:
            return None

    def service_token(self, cid):
        """Ключ для остатков и удержаний: отдельный, если задан, иначе основной."""
        return self.token(cid, "svc") or self.token(cid)

    # --- сессии
    def create_session(self, uid):
        token = secrets.token_urlsafe(32)
        with self.lock:
            now = time.time()
            self.data["sessions"] = {k: v for k, v in self.data["sessions"].items() if v["exp"] > now}
            self.data["sessions"][hashlib.sha256(token.encode()).hexdigest()] = {"uid": uid, "exp": now + SESSION_TTL}
            self.save()
        return token

    def session_user(self, token):
        if not token:
            return None
        s = self.data["sessions"].get(hashlib.sha256(token.encode()).hexdigest())
        if not s or s["exp"] < time.time():
            return None
        return self.user(s["uid"])

    def drop_session(self, token):
        with self.lock:
            if self.data["sessions"].pop(hashlib.sha256(token.encode()).hexdigest(), None):
                self.save()

    def drop_user_sessions(self, uid):
        with self.lock:
            self.data["sessions"] = {k: v for k, v in self.data["sessions"].items() if v["uid"] != uid}
            self.save()

    def migrate_single_key(self):
        """Ключ от прошлой версии (data/wb-key.enc) становится первым кабинетом."""
        old = self.dir / "wb-key.enc"
        if self.cabinets() or not old.exists():
            return
        cid = self.new_id()
        os.replace(old, self.dir / f"wb-key-{cid}.enc")
        with self.lock:
            self.cabinets().append({"id": cid, "name": "Кабинет 1"})
            self.save()


def public_user(u):
    return {k: u.get(k) for k in ("id", "login", "name", "role", "driverName", "driverId", "percent", "cabinetId")}


ROLES = ("admin", "owner", "driver")  # администратор, ИП (свой кабинет), водитель (только себя)


def remains_of_cabinet(items, cab):
    """Остатки одного кабинета: по ID поставщика, а если его нет — по названию ИП."""
    ids = {str(x) for x in cab.get("supplierIds") or []}
    name = norm_name(cab["name"])
    return [i for i in items if any((c.get("id") and str(c["id"]) in ids) or norm_name(c.get("name")) == name
                                    for c in i.get("carrierList") or [])]


def user_matches(u, t):
    """Относится ли путевой к сотруднику: по ID водителя WB, иначе по ФИО."""
    if u.get("driverId") not in (None, ""):
        return str(t.get("driverId")) == str(u["driverId"])
    return norm_name(t.get("driverName")) == norm_name(u.get("driverName") or u.get("name"))


def iso_time(ts):
    return datetime.fromtimestamp(ts, timezone.utc).isoformat() if ts else None


def fmt_rub(k):
    sign = "-" if k < 0 else ""
    rub, kop = divmod(abs(k), 100)
    return f"{sign}{rub:,}".replace(",", " ") + f",{kop:02d}"


def ru_date(s):
    y, m, d = s.split("-")
    return f"{d}.{m}.{y}"


def fine_type_line(kind, amount):
    if kind == NO_TYPE:
        return f"Штраф без указания вида всего {fmt_rub(amount)}"
    if kind.casefold().startswith("штраф"):
        return f"{kind} всего {fmt_rub(amount)}"
    return f"Штраф «{kind}» всего {fmt_rub(amount)}"


def report_text(name, date_from, date_to, row, percent, deduct_pending=True, holds_by_type=None):
    pct = f"{percent:g}".replace(".", ",")
    lines = [
        f"Зарплата партнера: {name}",
        f"Период: {ru_date(date_from)} - {ru_date(date_to)}",
        f"Путевых записей: {row['tripsCount']}",
        f"Сумма всех путевых: {fmt_rub(row['sum'])}",
    ]
    if percent:  # при нулевой комиссии строки о ней не нужны
        lines += [
            f"Минус комиссия ({pct}%): {fmt_rub(row['retention'])}",
            f"После комиссии: {fmt_rub(row['sum'] - row['retention'])}",
        ]
    by_type = row.get("finesByType") or []
    # Разбивку по видам пишем, только когда WB прислал виды штрафов.
    if any(f["type"] != NO_TYPE for f in by_type):
        lines += [fine_type_line(f["type"], f["sum"]) for f in by_type]
    lines.append(f"Сумма штрафов: {fmt_rub(row['fines'])}")
    if row.get("finesPending"):
        lines.append(f"В т.ч. на рассмотрении: {fmt_rub(row['finesPending'])}" if deduct_pending
                     else f"Штрафы на рассмотрении (не вычтены): {fmt_rub(row['finesPending'])}")
    lines.append(f"Минус штрафы от путевых: {fmt_rub(row['fines'])}")
    if holds_by_type is not None:
        lines += [f"Удержание WB «{h['type']}» всего {fmt_rub(h['sum'])}" for h in holds_by_type]
        lines.append(f"Минус удержания WB: {fmt_rub(sum(h['sum'] for h in holds_by_type))}")
    lines.append(f"Прибыль: {fmt_rub(row['payout'])}")
    return "\n".join(lines)


# ---------------------------------------------------------------- приложение

REMAINS_MIN_GAP = 60  # чаще раза в минуту остатки одного кабинета у WB не спрашиваем


class App:
    def __init__(self, data_dir, fetcher=None, post=None, get=None):
        self.db = Db(data_dir)
        self.db.migrate_single_key()
        self.cache = WaysheetCache(fetcher)
        self.wb_cache = TtlCache()
        self.post = post or post_json
        self.get = get or get_json
        self.index_lock = threading.Lock()
        self.index = None  # номер путевого -> водитель, для удержаний
        self.lookback_tried = {}  # (кабинет, период) -> когда искали ненайденные путевые
        self.login_failures = {}
        self.remains_status = {}  # кабинет -> {"at", "ok", "error", "count"}
        self.remains_locks = {}

    def percent_for(self, user):
        p = user.get("percent") if user else None
        return p if p is not None else self.db.data["settings"]["percent"]

    def deduct_pending(self):
        """Вычитать ли штрафы, которые WB ещё рассматривает."""
        return self.db.data["settings"].get("deductPending", True)

    # --- остатки
    def remains_file(self):
        return self.db.dir / "remains.json"

    def load_remains(self):
        try:
            return json.loads(self.remains_file().read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return {"items": []}

    def save_remains(self, new_items, file_name, user_name, supplier_ids=()):
        """Новые данные заменяют маршруты своих перевозчиков, остальные остаются.

        supplier_ids — перевозчики, чьи маршруты заменить, даже если в новых
        данных их маршрутов уже нет (все уехали).
        """
        now = time.strftime("%Y-%m-%dT%H:%M:%S%z")

        def names(i):
            return {c["name"] for c in i.get("carrierList") or []} or {""}

        def ids(i):
            return {str(c["id"]) for c in i.get("carrierList") or [] if c.get("id")}

        carriers = set().union(*(names(i) for i in new_items)) if new_items else set()
        replace_ids = {str(x) for x in supplier_ids}
        with self.db.lock:
            kept = [i for i in self.load_remains()["items"]
                    if not names(i) & carriers and not ids(i) & replace_ids]
            for i in new_items:
                i.update({"uploadedAt": now, "fileName": file_name, "uploadedBy": user_name})
            data = {"items": kept + new_items, "uploadedAt": now}
            self.db.dir.mkdir(parents=True, exist_ok=True)
            tmp = self.remains_file().with_suffix(".tmp")
            tmp.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
            os.replace(tmp, self.remains_file())
        return data

    def supplier_ids(self, cab):
        """ID поставщика кабинета: из настроек, иначе — из путевых WB за сегодня/вчера."""
        if cab.get("supplierIds"):
            return cab["supplierIds"]
        token = self.db.token(cab["id"])
        today = time.strftime("%Y-%m-%d")
        found = set()
        for day in (today, add_days(today, -1)):
            try:
                trips, _ = self.cache.get(cab["id"], token, day, add_days(day, 1))
            except WbError:
                break
            found |= {str(t["supplierId"]) for t in trips if isinstance(t, dict) and str(t.get("supplierId") or "").isdigit()}
            if found:
                break
        if found:
            with self.db.lock:
                cab["supplierIds"] = sorted(found)
                self.db.save()
            return cab["supplierIds"]
        raise WbError("NO_SUPPLIER", f"{cab['name']}: не знаю ID поставщика. Укажите его в Настройки → Кабинеты "
                                     "(число перед названием ИП в выгрузке остатков, например 10105118).")

    def refresh_remains(self, cid, force=False):
        """Остатки кабинета из WB. Не чаще раза в минуту, запросы одного кабинета — по очереди."""
        cab = self.db.cabinet(cid)
        if not cab:
            return
        lock = self.remains_locks.setdefault(cid, threading.Lock())
        with lock:
            st = self.remains_status.get(cid, {})
            if st.get("tried") and time.time() - st["tried"] < (REMAINS_MIN_GAP if force else self.remains_interval()):
                return
            st = {**st, "tried": time.time()}
            self.remains_status[cid] = st
            try:
                token = self.db.service_token(cid)
                if not token:
                    raise WbError("NO_KEY", f"{cab['name']}: ключ не задан.")
                sup = self.supplier_ids(cab)
                try:
                    items = fetch_remains(token, sup, self.post)
                except WbError as e:
                    if e.code == "KEY_EXPIRED":
                        own = "Ключ остатков и удержаний" if self.db.token(cid, "svc") else "Основной ключ"
                        raise WbError(e.code, f"{cab['name']}: {own} не подошёл для остатков. Вставьте ключ "
                                              "из запроса by-routecar в Настройки → Кабинеты → «Ключ остатков "
                                              f"и удержаний». {e}", e.status)
                    raise
                self.save_remains(items, "автозагрузка WB", cab["name"], sup)
                self.remains_status[cid] = {**st, "ok": time.time(), "error": None, "count": len(items)}
            except WbError as e:
                self.remains_status[cid] = {**st, "error": str(e)}

    def remains_interval(self):
        return max(1, int(self.db.data["settings"].get("remainsMinutes", 10))) * 60

    def remains_loop(self):
        """Фоновое обновление остатков по всем кабинетам."""
        while True:
            if self.db.data["settings"].get("remainsMinutes", 10):
                for cab in list(self.db.cabinets()):
                    try:
                        self.refresh_remains(cab["id"])
                    except Exception as e:  # фоновый поток не должен падать
                        print("Остатки:", type(e).__name__, file=sys.stderr)
            time.sleep(30)

    def parking_by_route(self):
        return {str(i["routeId"]): i.get("parking") for i in self.load_remains()["items"] if i.get("parking")}

    def load_trips(self, date_from, date_to, cabinet_ids=None):
        """Путевые за период из WB (to + 1 день), по всем или выбранным кабинетам."""
        trips, fetched, errors = [], [], []
        for cab in self.db.cabinets():
            if cabinet_ids is not None and cab["id"] not in cabinet_ids:
                continue
            try:
                items, at = self.cache.get(cab["id"], self.db.token(cab["id"]), date_from, add_days(date_to, 1))
            except WbError as e:
                errors.append((cab, e))
                continue
            fetched.append(at)
            trips.extend({**t, "_cabinet": cab["id"], "_cabinetName": cab["name"]} for t in items if isinstance(t, dict))
            self.remember_waysheets(cab["id"], items)
        if errors and not fetched:
            cab, e = errors[0]
            raise WbError(e.code, f"{cab['name']}: {e}" if len(self.db.cabinets()) > 1 else str(e), e.status)
        if not self.db.cabinets():
            raise WbError("NO_KEY", "Кабинеты ещё не добавлены. Администратор добавляет их в настройках.")
        warnings = [f"{cab['name']}: {e}" for cab, e in errors]
        return trips, (max(fetched) if fetched else None), warnings

    # --- путевой -> водитель (для удержаний, которые знают только номер путевого)
    def index_file(self):
        return self.db.dir / "waysheet-index.json"

    def waysheet_index(self):
        with self.index_lock:
            if self.index is None:
                try:
                    self.index = json.loads(self.index_file().read_text(encoding="utf-8"))
                except (OSError, ValueError):
                    self.index = {}
            return self.index

    def remember_waysheets(self, cid, items):
        index = self.waysheet_index()
        added = False
        with self.index_lock:
            for t in items:
                if not isinstance(t, dict) or t.get("id") is None:
                    continue
                wid = str(t["id"])
                entry = {"driverId": t.get("driverId"), "driverName": t.get("driverName"), "cabinet": cid,
                         "openDt": t.get("openDt"), "closeDt": t.get("closeDt")}
                if index.get(wid) != entry:
                    index[wid] = entry
                    added = True
            if added:
                self.db.dir.mkdir(parents=True, exist_ok=True)
                tmp = self.index_file().with_suffix(".tmp")
                tmp.write_text(json.dumps(index, ensure_ascii=False), encoding="utf-8")
                os.replace(tmp, self.index_file())

    # --- удержания WB
    def load_holds(self, date_from, date_to, cabinet_ids=None):
        """Удержания WB по кабинетам за период (по дате удержания), с водителем по номеру путевого."""
        items, fetched, warnings = [], [], []
        for cab in self.db.cabinets():
            cid = cab["id"]
            if cabinet_ids is not None and cid not in cabinet_ids:
                continue
            token = self.db.service_token(cid)
            try:
                if not token:
                    raise WbError("NO_KEY", "ключ не задан.")
                for sup in self.supplier_ids(cab):
                    rows, at = self.wb_cache.get(
                        ("holds", cid, sup, date_from, date_to),
                        lambda sup=sup: fetch_pretensions(token, sup, date_from, date_to, self.post))
                    fetched.append(at)
                    items += [{**r, "cabinetId": cid, "cabinetName": cab["name"]} for r in rows]
            except WbError as e:
                warnings.append(f"{cab['name']}: {e}")
        if warnings and not fetched and self.db.cabinets():
            raise WbError("WB_HOLDS", "Удержания не загрузились. " + " ".join(warnings))

        # Путевые, которых ещё нет в справочнике, ищем в WB за месяц до периода.
        index = self.waysheet_index()
        missing = {i["cabinetId"] for i in items if i["waysheetId"] and i["waysheetId"] not in index}
        for cid in missing:
            key = (cid, date_from, date_to)
            if time.time() - self.lookback_tried.get(key, 0) < 600:
                continue
            self.lookback_tried[key] = time.time()
            try:
                self.load_trips(add_days(date_from, -31), date_to, [cid])
            except WbError as e:
                warnings.append(f"{self.db.cabinet(cid)['name']}: не удалось найти водителей по путевым ({e})")
        index = self.waysheet_index()
        for i in items:
            w = index.get(i["waysheetId"] or "") or {}
            i.update({"driverId": w.get("driverId"), "driverName": w.get("driverName"),
                      "waysheetCloseDt": w.get("closeDt")})
        items.sort(key=lambda i: str(i["createdAt"]), reverse=True)
        return items, (max(fetched) if fetched else None), warnings

    def holds_for(self, user, date_from, date_to, cabinet_ids=None):
        items, at, warnings = self.load_holds(date_from, date_to, cabinet_ids)
        mine = [i for i in items if i["driverName"] and user_matches(user, i)]
        return mine, at, warnings

    def apply_holds(self, rep, date_from, date_to, deduct_holds, warnings, cabinet_ids=None):
        """Удержания WB в ведомость: по водителю, вычитаются из прибыли, если deduct_holds.

        Водитель с удержаниями, но без закрытых путевых в периоде, добавляется строкой без рейсов.
        """
        for r in rep["drivers"]:
            r.update({"holds": 0, "holdsByType": []})
        if not self.db.cabinets():
            return {"deductHolds": deduct_holds, "holdsUnmatched": {"count": 0, "sum": 0}}
        try:
            items, _, hw = self.load_holds(date_from, date_to, cabinet_ids)
            warnings += hw
        except WbError as e:
            warnings.append(f"Удержания WB не загрузились: {e}")
            items = []
        rows = rep["drivers"]
        unmatched = []
        for h in items:
            if h["refunded"]:
                continue
            if not h["driverName"]:
                unmatched.append(h)
                continue
            row = next((r for r in rows if user_matches({"driverId": r["driverId"], "driverName": r["driverName"]}, h)), None)
            if row is None:
                row = {"driverId": h["driverId"], "driverName": h["driverName"], "plates": [], "tripsCount": 0,
                       "sum": 0, "retention": 0, "fines": 0, "finesPending": 0, "finesByType": [],
                       "finesByStatus": [], "payout": 0, "hasFineUnderConsideration": False, "trips": [],
                       "holds": 0, "holdsByType": []}
                rows.append(row)
            row["holds"] += h["amount"]
            t = next((x for x in row["holdsByType"] if x["type"] == h["typeName"]), None)
            if t is None:
                row["holdsByType"].append({"type": h["typeName"], "sum": h["amount"]})
            else:
                t["sum"] += h["amount"]
        for r in rows:
            r["payoutBeforeHolds"] = r["payout"]
            if deduct_holds:
                r["payout"] -= r["holds"]
        rows.sort(key=lambda r: r["driverName"].lower())
        totals = {k: sum(r[k] for r in rows)
                  for k in ("tripsCount", "sum", "retention", "fines", "finesPending", "holds", "payout")}
        return {"drivers": rows, "totals": totals, "deductHolds": deduct_holds,
                "holdsUnmatched": {"count": len(unmatched), "sum": sum(h["amount"] for h in unmatched)}}

    def employee_report(self, user, date_from, date_to, deduct_holds=True, cabinet_ids=None):
        """Отчёт водителя. deduct_holds — вычитать удержания WB (переключатель на странице отчёта)."""
        trips, fetched_at, warnings = self.load_trips(date_from, date_to, cabinet_ids)
        mine = [{**t, "driverId": "me", "driverName": user["name"]} for t in trips if user_matches(user, t)]
        percent = self.percent_for(user)
        deduct = self.deduct_pending()
        rep = build_report(mine, date_from, date_to, percent, deduct)
        holds, holds_by_type, holds_error = [], [], None
        if self.db.cabinets():
            try:
                holds, _, hw = self.holds_for(user, date_from, date_to, cabinet_ids)
                warnings = warnings + hw
            except WbError as e:
                holds_error = str(e)
        by_type = {}
        for h in holds:
            if not h["refunded"]:
                by_type[h["typeName"]] = by_type.get(h["typeName"], 0) + h["amount"]
        holds_by_type = [{"type": k, "sum": v} for k, v in sorted(by_type.items())]
        holds_total = sum(by_type.values())
        row = rep["drivers"][0] if rep["drivers"] else {
            "tripsCount": 0, "sum": 0, "retention": 0, "fines": 0, "finesPending": 0, "payout": 0,
            "finesByType": [], "finesByStatus": [], "trips": []}
        if deduct_holds:
            row = {**row, "payout": row["payout"] - holds_total}
        if deduct_holds and holds_error:
            warnings = warnings + [f"Удержания WB не учтены: {holds_error}"]
        return {
            "employee": public_user(user), "from": date_from, "to": date_to, "percent": percent,
            "tripsCount": row["tripsCount"], "sum": row["sum"], "retention": row["retention"],
            "afterRetention": row["sum"] - row["retention"], "fines": row["fines"], "payout": row["payout"],
            "holds": holds_total, "holdsByType": holds_by_type, "holdsCount": len(holds),
            "deductHolds": deduct_holds, "holdsError": holds_error,
            "finesPending": row["finesPending"], "finesByType": row["finesByType"],
            "finesByStatus": row["finesByStatus"], "deductPending": deduct,
            "trips": row["trips"], "inTransit": len(rep["inTransit"]),
            "text": report_text(user["name"], date_from, date_to, row, percent, deduct,
                                holds_by_type if deduct_holds else None),
            "fetchedAt": fetched_at, "warnings": warnings,
        }


def waysheet_card(t, parking=None):
    v = trip_view(t)
    v.update({"driverName": t.get("driverName") or "—", "driverId": t.get("driverId"),
              "cabinetId": t.get("_cabinet"), "cabinetName": t.get("_cabinetName") or "",
              "total": v["price"] - v["fine"],
              # Парковку WB даёт в выгрузке остатков, связываем по ID маршрута.
              "parking": (parking or {}).get(str(t.get("logRouteId")))})
    return v


# ---------------------------------------------------------------- HTTP

STATIC = {
    "/": ("index.html", "text/html; charset=utf-8"),
    "/app.js": ("app.js", "text/javascript; charset=utf-8"),
    "/style.css": ("style.css", "text/css; charset=utf-8"),
}

SECURITY_HEADERS = {
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    "Referrer-Policy": "no-referrer",
    "Content-Security-Policy": "default-src 'self'; img-src 'self' data:; style-src 'self'; "
                               "script-src 'self'; connect-src 'self'; frame-ancestors 'none'",
}


class HttpError(Exception):
    def __init__(self, status, code, message):
        super().__init__(message)
        self.status, self.code = status, code


def bad(code, message, status=400):
    return HttpError(status, code, message)


def make_handler(app):
    db = app.db

    class Handler(BaseHTTPRequestHandler):
        server_version = "logistica-wb"

        def log_message(self, fmt, *args):
            pass  # запросы в консоль не пишем

        # --- ответ
        def send(self, status, body, headers=None):
            if isinstance(body, (bytes, str)):
                data = body.encode() if isinstance(body, str) else body
                ctype = None
            else:
                data = json.dumps(body, ensure_ascii=False).encode()
                ctype = "application/json; charset=utf-8"
            self.send_response(status)
            for k, v in SECURITY_HEADERS.items():
                self.send_header(k, v)
            self.send_header("Cache-Control", "no-store")
            if ctype:
                self.send_header("Content-Type", ctype)
            for k, v in (headers or {}).items():
                self.send_header(k, v)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        # --- запрос
        def body(self):
            n = int(self.headers.get("Content-Length") or 0)
            if n > 64 * 1024:
                raise bad("BAD_JSON", "Слишком большой запрос.")
            try:
                data = json.loads(self.rfile.read(n) or b"{}")
            except ValueError:
                raise bad("BAD_JSON", "Неверный запрос.")
            if not isinstance(data, dict):
                raise bad("BAD_JSON", "Неверный запрос.")
            return data

        def cookie(self):
            m = re.search(rf"(?:^|;\s*){COOKIE}=([^;]+)", self.headers.get("Cookie") or "")
            return m.group(1) if m else None

        def current_user(self):
            return db.session_user(self.cookie())

        def need_user(self):
            u = self.current_user()
            if not u:
                raise bad("AUTH", "Войдите заново.", 401)
            return u

        def need_admin(self):
            u = self.need_user()
            if u["role"] != "admin":
                raise bad("FORBIDDEN", "Доступно только администратору.", 403)
            return u

        def need_manager(self):
            """Администратор или ИП — те, кто видит всех водителей (ИП — только своего кабинета)."""
            u = self.need_user()
            if u["role"] not in ("admin", "owner"):
                raise bad("FORBIDDEN", "Нет доступа.", 403)
            self.scope(u)
            return u

        def scope(self, u):
            """Кабинеты, доступные пользователю; None — все."""
            if u["role"] != "owner":
                return None
            if not db.cabinet(u.get("cabinetId")):
                raise bad("FORBIDDEN", "ИП не привязан к кабинету. Обратитесь к администратору.", 403)
            return [u["cabinetId"]]

        def client_ip(self):
            if os.environ.get("TRUST_PROXY") and self.headers.get("X-Forwarded-For"):
                return self.headers["X-Forwarded-For"].split(",")[0].strip()
            return self.client_address[0]

        def cookie_attrs(self):
            secure = os.environ.get("COOKIE_SECURE") or self.headers.get("X-Forwarded-Proto") == "https"
            return "Path=/; HttpOnly; SameSite=Strict" + ("; Secure" if secure else "")

        def same_origin(self):
            origin = self.headers.get("Origin")
            return not origin or urlparse(origin).netloc == self.headers.get("Host")

        def period(self, q):
            date_from = (q.get("from") or [None])[0]
            date_to = (q.get("to") or [date_from])[0]
            if not valid_date(date_from) or not valid_date(date_to):
                raise bad("BAD_DATE", "Неверная дата.")
            if date_from > date_to:
                raise bad("BAD_DATE", "Начало периода позже конца.")
            if add_days(date_from, MAX_PERIOD_DAYS) <= date_to:
                raise bad("BAD_DATE", f"Период не длиннее {MAX_PERIOD_DAYS} дней.")
            return date_from, date_to

        # --- маршрутизация
        def route(self, method):
            url = urlparse(self.path)
            path, q = url.path, parse_qs(url.query)
            if method == "GET" and path in STATIC:
                name, ctype = STATIC[path]
                return self.send(200, (PUBLIC_DIR / name).read_bytes(),
                                 {"Content-Type": ctype, "Cache-Control": "no-cache"})
            if method != "GET" and not self.same_origin():
                raise bad("FORBIDDEN", "Чужой источник запроса.", 403)

            handler, item_id = ROUTES.get((method, path)), None
            m = re.match(r"^(.*)/([0-9a-f]{8})$", path)
            if not handler and m:
                handler, item_id = ROUTES.get((method, m.group(1) + "/:id")), m.group(2)
            if not handler:
                raise bad("NOT_FOUND", "Не найдено.", 404)
            result = handler(self, q, item_id)
            if result is not None:
                self.send(200, result)

        # ======== вход
        def api_me(self, q, _):
            u = self.current_user()
            return {"user": public_user(u) if u else None, "needsSetup": not db.users(),
                    "setupNeedsCode": bool(os.environ.get("SETUP_CODE")) and not db.users()}

        def api_setup(self, q, _):
            if db.users():
                raise bad("FORBIDDEN", "Администратор уже создан.", 403)
            b = self.body()
            # На сервере в интернете первого администратора создаёт только тот, кто знает код из установки.
            code = os.environ.get("SETUP_CODE")
            if code and not hmac.compare_digest(str(b.get("code", "")).strip().encode(), code.encode()):
                raise bad("BAD_CODE", "Неверный код установки. Он напечатан в конце установки и лежит в /etc/logistica-wb.env.", 403)
            user = self.make_user(b, role="admin")
            with db.lock:
                db.users().append(user)
                db.save()
            return self.start_session(user)

        def api_login(self, q, _):
            ip = self.client_ip()
            now = time.time()
            fails = [t for t in app.login_failures.get(ip, []) if now - t < 600]
            if len(fails) >= 10:
                raise bad("RATE", "Слишком много попыток. Подождите 10 минут.", 429)
            b = self.body()
            u = db.user_by_login(b.get("login", ""))
            if not u or not check_password(u, str(b.get("password", ""))):
                app.login_failures[ip] = fails + [now]
                raise bad("BAD_LOGIN", "Неверный логин или пароль.", 401)
            app.login_failures.pop(ip, None)
            return self.start_session(u)

        def start_session(self, u):
            token = db.create_session(u["id"])
            self.send(200, {"user": public_user(u)}, {
                "Set-Cookie": f"{COOKIE}={token}; {self.cookie_attrs()}; Max-Age={SESSION_TTL}"})

        def api_logout(self, q, _):
            if self.cookie():
                db.drop_session(self.cookie())
            self.send(200, {"ok": True}, {"Set-Cookie": f"{COOKIE}=; {self.cookie_attrs()}; Max-Age=0"})

        def api_password(self, q, _):
            u = self.need_user()
            b = self.body()
            if not check_password(u, str(b.get("old", ""))):
                raise bad("BAD_LOGIN", "Старый пароль неверный.")
            new = str(b.get("new", ""))
            if len(new) < 6:
                raise bad("BAD_PASSWORD", "Пароль — не короче 6 символов.")
            with db.lock:
                u["salt"], u["hash"] = hash_password(new)
                db.save()
            return {"ok": True}

        # ======== сотрудники
        def make_user(self, b, role=None, existing=None):
            name = " ".join(str(b.get("name", "")).split())
            login = str(b.get("login", "")).strip()
            if not existing or "name" in b:
                if not name:
                    raise bad("BAD_USER", "Укажите ФИО.")
            if not existing or "login" in b:
                if not re.match(r"^[\w.@-]{3,40}$", login):
                    raise bad("BAD_USER", "Логин — от 3 символов: буквы, цифры, точка, дефис.")
                other = db.user_by_login(login)
                if other and other is not existing:
                    raise bad("BAD_USER", "Такой логин уже есть.")
            u = existing if existing is not None else {"id": db.new_id()}
            if not existing or "name" in b:
                u["name"] = name
            if not existing or "login" in b:
                u["login"] = login
            if role or "role" in b or not existing:
                r = role or b.get("role") or "owner"
                if r not in ROLES:
                    raise bad("BAD_USER", "Неверная роль.")
                u["role"] = r
            if "cabinetId" in b or not existing:
                u["cabinetId"] = str(b.get("cabinetId") or "") or None
            if u["role"] == "owner" and not db.cabinet(u.get("cabinetId")):
                raise bad("BAD_USER", "Выберите кабинет ИП.")
            for f in ("driverName", "driverId"):
                if f in b or not existing:
                    v = str(b.get(f) or "").strip()
                    u[f] = v or None
            if "percent" in b or not existing:
                raw = b.get("percent")
                if raw in (None, ""):
                    u["percent"] = None
                else:
                    p = parse_percent(raw)
                    if p is None:
                        raise bad("BAD_PERCENT", "Процент должен быть от 0 до 100.")
                    u["percent"] = p
            if b.get("password") or not existing:
                pw = str(b.get("password") or "")
                if len(pw) < 6:
                    raise bad("BAD_PASSWORD", "Пароль — не короче 6 символов.")
                u["salt"], u["hash"] = hash_password(pw)
            return u

        def api_users(self, q, _):
            self.need_admin()
            return {"users": [public_user(u) for u in db.users()]}

        def api_user_create(self, q, _):
            self.need_admin()
            u = self.make_user(self.body())
            with db.lock:
                db.users().append(u)
                db.save()
            return public_user(u)

        def api_user_update(self, q, uid):
            me = self.need_admin()
            u = db.user(uid)
            if not u:
                raise bad("NOT_FOUND", "Сотрудник не найден.", 404)
            b = self.body()
            if u is me and b.get("role") not in (None, "admin"):
                raise bad("BAD_USER", "Нельзя снять права администратора с себя.")
            with db.lock:
                self.make_user(b, existing=u)
                db.save()
            if b.get("password"):
                db.drop_user_sessions(u["id"])
            return public_user(u)

        def api_user_delete(self, q, uid):
            me = self.need_admin()
            if uid == me["id"]:
                raise bad("BAD_USER", "Нельзя удалить себя.")
            with db.lock:
                db.data["users"] = [u for u in db.users() if u["id"] != uid]
                db.save()
            db.drop_user_sessions(uid)
            return {"ok": True}

        # ======== кабинеты
        def cabinet_info(self, c, admin):
            info = {"id": c["id"], "name": c["name"],
                    "lastSuccess": app.cache.last_success.get(c["id"])}
            if admin:
                info["supplierIds"] = c.get("supplierIds") or []
                try:
                    info["key2"] = key_info(db.key_store(c["id"], "svc").load())
                except Exception:
                    info["key2"] = {"configured": False, "broken": True}
                try:
                    info["key"] = key_info(db.key_store(c["id"]).load())
                except Exception:
                    info["key"] = {"configured": False, "broken": True}
            return info

        def api_cabinets(self, q, _):
            u = self.need_user()
            allowed = self.scope(u)
            return {"cabinets": [self.cabinet_info(c, u["role"] == "admin") for c in db.cabinets()
                                 if allowed is None or c["id"] in allowed]}

        def save_key(self, cid, value, kind="main"):
            token = extract_token(value, any_format=kind == "svc")
            if not token:
                if kind == "svc":
                    raise bad("BAD_KEY", "Не нашёл ключ. Вставьте значение заголовка Authorization из запроса "
                                         "остатков (by-routecar) — с «Bearer» или без.")
                raise bad("BAD_KEY", "Не нашёл ключ. Вставьте адрес страницы ol.wildberries.ru целиком "
                                     "(с «bearer=») или сам ключ.")
            if key_info(token)["expired"]:
                raise bad("KEY_EXPIRED", "Этот ключ уже истёк — возьмите свежий. Если ключ точно свежий, "
                                         "проверьте дату и время на компьютере.")
            try:
                db.key_store(cid, kind).save(token)
            except RuntimeError as e:
                raise bad("NO_CRYPTO", str(e), 500)
            app.cache.clear(cid)
            app.wb_cache.clear()
            app.remains_status.pop(cid, None)

        @staticmethod
        def parse_suppliers(raw):
            parts = re.split(r"[\s,;]+", str(raw or "").strip()) if not isinstance(raw, list) else [str(x) for x in raw]
            parts = [p for p in parts if p]
            if any(not p.isdigit() for p in parts):
                raise bad("BAD_CABINET", "ID поставщика — число, например 10105118.")
            return parts

        def api_cabinet_create(self, q, _):
            self.need_admin()
            b = self.body()
            name = " ".join(str(b.get("name", "")).split())
            if not name:
                raise bad("BAD_CABINET", "Укажите название кабинета, например «ИП Иванов Иван».")
            cid = db.new_id()
            sup = self.parse_suppliers(b.get("supplierIds"))
            self.save_key(cid, b.get("value"))
            if b.get("value2"):
                self.save_key(cid, b["value2"], "svc")
            with db.lock:
                db.cabinets().append({"id": cid, "name": name, "supplierIds": sup})
                db.save()
            return self.cabinet_info(db.cabinet(cid), True)

        def api_cabinet_update(self, q, cid):
            self.need_admin()
            c = db.cabinet(cid)
            if not c:
                raise bad("NOT_FOUND", "Кабинет не найден.", 404)
            b = self.body()
            if b.get("value"):
                self.save_key(cid, b["value"])
            if b.get("value2"):
                self.save_key(cid, b["value2"], "svc")
            if b.get("clearValue2"):
                db.key_store(cid, "svc").remove()
                app.remains_status.pop(cid, None)
            if "supplierIds" in b:
                sup = self.parse_suppliers(b["supplierIds"])
                with db.lock:
                    c["supplierIds"] = sup
                    db.save()
                app.remains_status.pop(cid, None)
            if b.get("name"):
                with db.lock:
                    c["name"] = " ".join(str(b["name"]).split())
                    db.save()
            return self.cabinet_info(c, True)

        def api_cabinet_delete(self, q, cid):
            self.need_admin()
            db.key_store(cid).remove()
            db.key_store(cid, "svc").remove()
            app.cache.clear(cid)
            with db.lock:
                db.data["cabinets"] = [c for c in db.cabinets() if c["id"] != cid]
                for u in db.users():  # ИП удалённого кабинета больше ничего не видит
                    if u.get("cabinetId") == cid:
                        u["cabinetId"] = None
                db.save()
            return {"ok": True}

        # ======== настройки
        def api_settings(self, q, _):
            self.need_user()
            return {"percent": db.data["settings"]["percent"], "deductPending": app.deduct_pending(),
                    "remainsMinutes": db.data["settings"].get("remainsMinutes", 10)}

        def api_settings_save(self, q, _):
            self.need_admin()
            b = self.body()
            changes = {}
            if "percent" in b:
                p = parse_percent(b.get("percent"))
                if p is None:
                    raise bad("BAD_PERCENT", "Процент должен быть от 0 до 100.")
                changes["percent"] = p
            if "deductPending" in b:
                changes["deductPending"] = bool(b["deductPending"])
            if "remainsMinutes" in b:
                try:
                    m = int(b["remainsMinutes"])
                except (TypeError, ValueError):
                    m = -1
                if m != 0 and not 5 <= m <= 1440:
                    raise bad("BAD_SETTINGS", "Обновление остатков: 0 (выключено) или от 5 до 1440 минут.")
                changes["remainsMinutes"] = m
            with db.lock:
                db.data["settings"].update(changes)
                db.save()
            return self.api_settings(q, None)

        # ======== данные
        def target_user(self, q):
            u = self.need_user()
            driver = (q.get("driver") or [""])[0].strip()
            if driver:
                # Администратор и ИП смотрят отчёт любого водителя из WB (ИП — своего кабинета).
                if u["role"] not in ("admin", "owner"):
                    raise bad("FORBIDDEN", "Можно смотреть только свои данные.", 403)
                did = (q.get("driverId") or [""])[0].strip() or None
                return {"id": None, "name": driver, "driverName": driver, "driverId": did,
                        "role": "driver", "percent": None}
            uid = (q.get("employee") or [None])[0]
            if uid and uid != u["id"]:
                if u["role"] != "admin":
                    raise bad("FORBIDDEN", "Можно смотреть только свои данные.", 403)
                u = db.user(uid)
                if not u:
                    raise bad("NOT_FOUND", "Сотрудник не найден.", 404)
            return u

        def api_report(self, q, _):
            me = self.need_user()
            user = self.target_user(q)
            date_from, date_to = self.period(q)
            deduct_holds = (q.get("holds") or ["1"])[0] != "0"
            return app.employee_report(user, date_from, date_to, deduct_holds, self.scope(me))

        def api_fines(self, q, _):
            """Удержания WB: путевые со штрафами за период."""
            u = self.need_user()
            date_from, date_to = self.period(q)
            trips, fetched_at, warnings = app.load_trips(date_from, date_to, self.scope(u))
            rows = []
            for t in trips:
                if t.get("isDeleted") or (u["role"] == "driver" and not user_matches(u, t)):
                    continue
                day = day_of(t.get("closeDt") or t.get("openDt"))
                if not day or not (date_from <= day <= date_to):
                    continue
                card = waysheet_card(t)
                if card["fineStatus"]:
                    rows.append(card)
            rows.sort(key=lambda r: str(r["closeDt"] or r["openDt"]))
            # Сводка по всем штрафам периода, включая незакрытые путевые.
            summary = fines_summary(rows, deduct_pending=True)
            return {"items": rows, "total": summary["fines"], "byType": summary["finesByType"],
                    "byStatus": summary["finesByStatus"], "deductPending": app.deduct_pending(),
                    "fetchedAt": fetched_at, "warnings": warnings}

        def api_waysheets(self, q, _):
            u = self.need_user()
            day = (q.get("date") or [None])[0]
            if not valid_date(day):
                raise bad("BAD_DATE", "Неверная дата.")
            cid = (q.get("cabinet") or [None])[0]
            allowed = self.scope(u)
            if allowed is not None and not cid:
                cid = allowed[0]
            if not cid or not db.cabinet(cid) or (allowed is not None and cid not in allowed):
                raise bad("BAD_CABINET", "Выбери кабинет.")
            trips, fetched_at, _w = app.load_trips(day, day, [cid])
            parking = app.parking_by_route()
            items = []
            for t in trips:
                if t.get("isDeleted") or (u["role"] == "driver" and not user_matches(u, t)):
                    continue
                if day_of(t.get("closeDt") or t.get("openDt")) != day:
                    continue
                items.append(waysheet_card(t, parking))
            items.sort(key=lambda r: str(r["openDt"]))
            return {"items": items, "fetchedAt": fetched_at}

        def api_summary(self, q, _):
            u = self.need_manager()
            allowed = self.scope(u)
            date_from, date_to = self.period(q)
            raw = (q.get("percent") or [None])[0]
            percent = db.data["settings"]["percent"] if raw is None else parse_percent(raw)
            if percent is None:
                raise bad("BAD_PERCENT", "Процент должен быть от 0 до 100.")
            trips, fetched_at, warnings = app.load_trips(date_from, date_to, allowed)
            rep = build_report(trips, date_from, date_to, percent, app.deduct_pending())
            deduct_holds = (q.get("holds") or ["1"])[0] != "0"
            rep.update(app.apply_holds(rep, date_from, date_to, deduct_holds, warnings, allowed))
            return {**rep, "fetchedAt": fetched_at, "warnings": warnings}

        def api_drivers(self, q, _):
            """ФИО водителей из WB за период — для выбора в отчёте и привязки сотрудников."""
            u = self.need_manager()
            date_from, date_to = self.period(q)
            trips, _, _ = app.load_trips(date_from, date_to, self.scope(u))
            seen = {}
            for t in trips:
                name = t.get("driverName")
                if name:
                    seen.setdefault((name, str(t.get("driverId") or "")), 0)
                    seen[(name, str(t.get("driverId") or ""))] += 1
            return {"drivers": [{"driverName": n, "driverId": i or None, "trips": c}
                                for (n, i), c in sorted(seen.items(), key=lambda x: norm_name(x[0][0]))]}

        def api_raw(self, q, _):
            """Сырой ответ WB за день — для проверки полей. Ключа в нём нет."""
            self.need_admin()
            day = (q.get("date") or [None])[0]
            cid = (q.get("cabinet") or [None])[0]
            if not valid_date(day) or not db.cabinet(cid):
                raise bad("BAD_DATE", "Укажите день и кабинет.")
            items, _ = app.cache.get(cid, db.token(cid), day, add_days(day, 1))
            # WB отдаёт кусок в две недели — оставляем путевые, открытые или закрытые в этот день.
            items = [t for t in items if isinstance(t, dict) and day in (day_of(t.get("openDt")), day_of(t.get("closeDt")))]
            self.send(200, json.dumps(items, ensure_ascii=False, indent=2).encode(), {
                "Content-Type": "application/json; charset=utf-8",
                "Content-Disposition": f'attachment; filename="wb-{day}.json"'})

        # ======== удержания WB
        def api_holds(self, q, _):
            u = self.need_user()
            date_from, date_to = self.period(q)
            allowed = self.scope(u)
            if u["role"] in ("admin", "owner"):
                items, at, warnings = app.load_holds(date_from, date_to, allowed)
            else:
                items, at, warnings = app.holds_for(u, date_from, date_to)
            by_type = {}
            for i in items:
                if i["refunded"]:
                    continue
                t = by_type.setdefault(i["typeName"], {"type": i["typeName"], "sum": 0, "count": 0})
                t["sum"] += i["amount"]
                t["count"] += 1
            # Как на странице WB: строка = путевой + вид + причина, с количеством штук.
            groups = {}
            for i in items:
                key = (i["waysheetId"], i["typeName"], i["reason"], i["refunded"], i["day"])
                g = groups.get(key)
                if not g:
                    g = groups[key] = {k: i[k] for k in ("waysheetId", "typeName", "reason", "refunded", "day",
                                                         "createdAt", "driverName", "driverId", "cabinetName",
                                                         "shipDate", "officeName")}
                    g.update({"sum": 0, "count": 0})
                g["sum"] += i["amount"]
                g["count"] += 1
            unmatched = [i for i in items if not i["driverName"]]
            result = {
                "groups": list(groups.values()), "byType": sorted(by_type.values(), key=lambda t: -t["sum"]),
                "total": sum(t["sum"] for t in by_type.values()), "count": len(items),
                "unmatched": {"count": len(unmatched), "sum": sum(i["amount"] for i in unmatched)},
                "fetchedAt": at, "warnings": warnings,
            }
            if u["role"] in ("admin", "owner"):
                result["balances"] = []
                for cab in db.cabinets():
                    if allowed is not None and cab["id"] not in allowed:
                        continue
                    try:
                        for sup in cab.get("supplierIds") or []:
                            bal, _ = app.wb_cache.get(("balance", cab["id"], sup), lambda sup=sup, cab=cab: fetch_balance(
                                db.service_token(cab["id"]), sup, app.get))
                            result["balances"].append({"cabinet": cab["name"], **bal})
                    except WbError as e:
                        result["warnings"].append(f"{cab['name']}: баланс не загрузился ({e})")
            return result

        # ======== остатки
        def api_remains(self, q, _):
            u = self.need_user()
            allowed = self.scope(u)
            data = app.load_remains()
            items = data["items"]
            cabs = [c for c in db.cabinets() if allowed is None or c["id"] in allowed]
            if allowed is not None:
                items = remains_of_cabinet(items, cabs[0])
            items = sorted(items, key=lambda i: -(i.get("shk") or 0))
            carriers = sorted({c["name"] for i in items for c in i["carrierList"]})
            status = [{"cabinet": c["name"], "ok": iso_time(st.get("ok")), "error": st.get("error"),
                       "count": st.get("count")}
                      for c in cabs for st in [app.remains_status.get(c["id"], {})]]
            return {"items": items, "carriers": carriers, "uploadedAt": data.get("uploadedAt"),
                    "auto": status, "autoMinutes": db.data["settings"].get("remainsMinutes", 10)}

        def api_remains_refresh(self, q, _):
            u = self.need_user()
            allowed = self.scope(u)
            for c in list(db.cabinets()):
                if allowed is None or c["id"] in allowed:
                    app.refresh_remains(c["id"], force=True)
            return self.api_remains(q, None)

        def api_remains_upload(self, q, _):
            u = self.need_admin()
            n = int(self.headers.get("Content-Length") or 0)
            if not n or n > 10 * 1024 * 1024:
                raise bad("BAD_FILE", "Нужен файл выгрузки до 10 МБ.")
            name = (q.get("name") or ["остатки.xlsx"])[0][:200]
            try:
                items = parse_remains(self.rfile.read(n), name)
            except RemainsError as e:
                raise bad("BAD_FILE", str(e))
            except Exception:
                raise bad("BAD_FILE", "Не удалось прочитать файл. Загрузите выгрузку остатков из WB (.xlsx или .csv).")
            app.save_remains(items, name, u["name"])
            return {"loaded": len(items)}

        def api_remains_clear(self, q, _):
            self.need_admin()
            with db.lock:
                if app.remains_file().exists():
                    app.remains_file().unlink()
            return {"ok": True}

        # --- методы HTTP
        def handle_method(self, method):
            try:
                self.route(method)
            except HttpError as e:
                self.send(e.status, {"error": {"code": e.code, "message": str(e)}})
            except WbError as e:
                status = 409 if e.code in ("KEY_EXPIRED", "NO_KEY") else 502
                self.send(status, {"error": {"code": e.code, "message": str(e)}})
            except Exception as e:
                print("Ошибка:", type(e).__name__, e if not isinstance(e, OSError) else "", file=sys.stderr)
                try:
                    self.send(500, {"error": {"code": "INTERNAL", "message": "Внутренняя ошибка сервера."}})
                except OSError:
                    pass

        def do_GET(self):
            self.handle_method("GET")

        def do_POST(self):
            self.handle_method("POST")

        def do_PUT(self):
            self.handle_method("PUT")

        def do_DELETE(self):
            self.handle_method("DELETE")

    ROUTES = {
        ("GET", "/api/me"): Handler.api_me,
        ("POST", "/api/setup"): Handler.api_setup,
        ("POST", "/api/login"): Handler.api_login,
        ("POST", "/api/logout"): Handler.api_logout,
        ("POST", "/api/password"): Handler.api_password,
        ("GET", "/api/users"): Handler.api_users,
        ("POST", "/api/users"): Handler.api_user_create,
        ("PUT", "/api/users/:id"): Handler.api_user_update,
        ("DELETE", "/api/users/:id"): Handler.api_user_delete,
        ("GET", "/api/cabinets"): Handler.api_cabinets,
        ("POST", "/api/cabinets"): Handler.api_cabinet_create,
        ("PUT", "/api/cabinets/:id"): Handler.api_cabinet_update,
        ("DELETE", "/api/cabinets/:id"): Handler.api_cabinet_delete,
        ("GET", "/api/settings"): Handler.api_settings,
        ("POST", "/api/settings"): Handler.api_settings_save,
        ("GET", "/api/report"): Handler.api_report,
        ("GET", "/api/fines"): Handler.api_fines,
        ("GET", "/api/waysheets"): Handler.api_waysheets,
        ("GET", "/api/summary"): Handler.api_summary,
        ("GET", "/api/drivers"): Handler.api_drivers,
        ("GET", "/api/raw"): Handler.api_raw,
        ("GET", "/api/holds"): Handler.api_holds,
        ("GET", "/api/remains"): Handler.api_remains,
        ("POST", "/api/remains"): Handler.api_remains_upload,
        ("DELETE", "/api/remains"): Handler.api_remains_clear,
        ("POST", "/api/remains/refresh"): Handler.api_remains_refresh,
    }
    return Handler


class Server(ThreadingHTTPServer):
    # На Windows SO_REUSEADDR позволяет двум программам занять один порт — выключаем.
    allow_reuse_address = sys.platform != "win32"
    daemon_threads = True


def make_server(host, port, app=None):
    app = app or App(DATA_DIR)
    return Server((host, port), make_handler(app))


def main():
    app = App(DATA_DIR)
    threading.Thread(target=app.remains_loop, daemon=True).start()
    server = None
    port = PORT
    # Если порт занят (например, сайт уже запущен в другом окне), пробуем следующие.
    for port in range(PORT, PORT + 10):
        try:
            server = make_server(HOST, port, app)
            break
        except OSError:
            print(f"Порт {port} занят, пробую {port + 1}…")
    if server is None:
        sys.exit("Не нашёл свободный порт.")
    url = f"http://{'localhost' if HOST == '0.0.0.0' else HOST}:{port}"
    print(f"\n  Сайт запущен: {url}")
    if HOST == "0.0.0.0":
        print("  С телефона в той же Wi-Fi сети: http://<IP этого компьютера>:" + str(port))
    print("  Не закрывайте это окно, пока пользуетесь сайтом. Остановить: Ctrl+C.\n")
    if not os.environ.get("NO_BROWSER"):
        threading.Timer(0.5, webbrowser.open, (url,)).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("Остановлено.")


if __name__ == "__main__":
    main()
