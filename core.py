"""Расчёт зарплаты водителей по данным WB Логистики: деньги, даты, ключи, запрос к WB.

Формула (как в приложении):
    после удержания = сумма путевых - 20%
    водителю = после удержания - штрафы
Все суммы считаются в копейках, целыми числами.
"""
import base64
import hashlib
import json
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import date, datetime, timedelta, timezone
from decimal import ROUND_HALF_UP, Decimal, InvalidOperation
from pathlib import Path

# Москва живёт по UTC+3 без перехода на летнее время.
REPORT_TZ = timezone(timedelta(hours=float(os.environ.get("TZ_OFFSET_HOURS") or 3)))
CACHE_TTL = 60

URL = ("https://logistics.wb.ru/waysheets-netcore-service/api/v1/waysheet/"
       "external/receive-by-period")

# ---------------------------------------------------------------- деньги

def _parse_kopecks(v):
    if v is None or v == "" or isinstance(v, bool):
        return None
    s = re.sub(r"[\s\u00a0\u202f₽]", "", str(v)).replace(",", ".")
    try:
        d = Decimal(s)
    except InvalidOperation:
        return None
    if not d.is_finite():
        return None
    return int((d * 100).quantize(Decimal(1), ROUND_HALF_UP))


def kopecks(v):
    """Копейки из поля: null, число, строка или {'parsedValue': ..., 'source': ...}."""
    if isinstance(v, dict):
        for field in ("parsedValue", "source", "value"):
            k = _parse_kopecks(v.get(field))
            if k is not None:
                return k
        return 0
    k = _parse_kopecks(v)
    return 0 if k is None else k


def percent_of(kop, percent):
    """Процент от суммы в копейках, округлённый до копейки."""
    return int((Decimal(kop) * Decimal(str(percent)) / 100).quantize(Decimal(1), ROUND_HALF_UP))


def parse_percent(v):
    try:
        p = Decimal(str(v).replace(",", "."))
    except InvalidOperation:
        return None
    if not p.is_finite() or p < 0 or p > 100:
        return None
    return float(p.quantize(Decimal("0.01"), ROUND_HALF_UP))


# ---------------------------------------------------------------- даты

DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
OFFSET_RE = re.compile(r"(Z|[+-]\d{2}:?\d{2})$", re.I)


def valid_date(s):
    if not isinstance(s, str) or not DATE_RE.match(s):
        return False
    try:
        date.fromisoformat(s)
        return True
    except ValueError:
        return False


def add_days(s, n):
    return (date.fromisoformat(s) + timedelta(days=n)).isoformat()


def day_of(dt):
    """Календарный день метки времени. Без смещения — как есть (время WB московское)."""
    if not dt or not isinstance(dt, str):
        return None
    s = dt.strip()
    if not OFFSET_RE.search(s):
        return s[:10] if DATE_RE.match(s[:10]) else None
    try:
        iso = s[:-1] + "+00:00" if s[-1] in "zZ" else s
        # fromisoformat в старых Python не понимает больше 6 знаков дробной части
        iso = re.sub(r"(\.\d{6})\d+", r"\1", iso)
        return datetime.fromisoformat(iso).astimezone(REPORT_TZ).date().isoformat()
    except ValueError:
        return None


# ---------------------------------------------------------------- расчёт

def parse_route(name):
    """«Склад А (123), ПВЗ Б (456)» → [{name, id}]"""
    if not name or not isinstance(name, str):
        return []
    points = []
    for part in name.split(", "):
        m = re.match(r"^(.*?)\s*\((\d+)\)\s*$", part.strip())
        points.append({"name": m.group(1), "id": m.group(2)} if m else {"name": part.strip(), "id": None})
    return [p for p in points if p["name"] or p["id"]]


# ---------------------------------------------------------------- штрафы

FINE_PENDING = "pending"      # на рассмотрении
FINE_APPROVED = "approved"    # подтверждён
FINE_UNCONFIRMED = "unconfirmed"  # не подтверждён
FINE_CHARGED = "charged"      # начислен, без отметки о подтверждении
FINE_STATUS_TEXT = {
    FINE_PENDING: "На рассмотрении",
    FINE_APPROVED: "Подтверждён",
    FINE_UNCONFIRMED: "Не подтверждён",
    FINE_CHARGED: "Начислен",
}
NO_TYPE = "Без вида"

_SUM_KEYS = ("sum", "fineSum", "amount", "value", "price", "total", "totalSum")
_NAME_KEYS = ("fineTypeName", "typeName", "name", "reasonName", "reason", "title", "description", "type", "comment")
_SCALAR_TYPE_KEYS = ("fineTypeName", "fineType", "fineReason", "fineName", "fineDescription")


def fine_status(t):
    """Статус штрафа по признакам WB; None — штрафа нет."""
    if not kopecks(t.get("fineSum")) and not t.get("isFine") and not t.get("isFineUnderConsideration"):
        return None
    if t.get("isFineUnderConsideration"):
        return FINE_PENDING
    if t.get("isApproved") is True:
        return FINE_APPROVED
    if t.get("isApproved") is False:
        return FINE_UNCONFIRMED
    return FINE_CHARGED


def _first(d, keys):
    for k in keys:
        v = d.get(k)
        if v not in (None, "") and not isinstance(v, (list, dict, bool)):
            return v
    return None


def fine_parts(t):
    """Штраф путевого по видам: [{"type", "sum"}].

    Если WB присылает в путевом список штрафов (поле со словом fine/penalty,
    элементы с названием и суммой) или название вида строкой — берём вид оттуда.
    Иначе весь fineSum идёт одной строкой «Без вида».
    """
    total = kopecks(t.get("fineSum"))
    parts = []
    for key, value in t.items():
        low = key.lower()
        if ("fine" not in low and "penalt" not in low) or not isinstance(value, list):
            continue
        for item in value:
            if not isinstance(item, dict):
                continue
            amount = _first(item, _SUM_KEYS)
            if amount is None:
                continue
            name = _first(item, _NAME_KEYS)
            parts.append({"type": str(name).strip() if name not in (None, "") else NO_TYPE, "sum": kopecks(amount)})
    listed = sum(p["sum"] for p in parts)
    # Главная сумма — fineSum: список берём, только если он в неё укладывается,
    # остаток идёт строкой «Без вида».
    if parts and not (total and listed > total):
        if total > listed:
            parts.append({"type": NO_TYPE, "sum": total - listed})
        return [p for p in parts if p["sum"]]
    if not total:
        return []
    name = _first(t, _SCALAR_TYPE_KEYS)
    return [{"type": str(name).strip() if name not in (None, "") else NO_TYPE, "sum": total}]


def trip_view(t):
    ret = t.get("totalSumReturn")
    return {
        "id": t.get("id"),
        "openDt": t.get("openDt"),
        "closeDt": t.get("closeDt"),
        "vehicleNumberPlate": t.get("vehicleNumberPlate") or "",
        "price": kopecks(t.get("totalPrice")),
        "fine": sum(p["sum"] for p in fine_parts(t)),
        "fineParts": fine_parts(t),
        "fineStatus": fine_status(t),
        "fineStatusText": FINE_STATUS_TEXT.get(fine_status(t)),
        "isFine": bool(t.get("isFine")),
        "isFineUnderConsideration": bool(t.get("isFineUnderConsideration")),
        "isApproved": t.get("isApproved"),
        "shksCount": t.get("shksCount"),
        "boxCountTotal": t.get("boxCountTotal"),
        "boxCountDelivered": t.get("boxCountDelivered"),
        "planMileage": t.get("planMileage"),
        "totalSumReturn": None if ret is None else kopecks(ret),
        "logRouteId": t.get("logRouteId"),
        "logRouteName": t.get("logRouteName") or "",
        "routePoints": parse_route(t.get("logRouteName")),
        "srcOfficeName": t.get("srcOfficeName") or "",
    }


def fines_summary(trips, deduct_pending=True):
    """Штрафы водителя: сколько вычесть, по видам и по статусам."""
    by_type, by_status = {}, {}
    deducted = pending = 0
    for x in trips:
        if not x["fine"]:
            continue
        status = x["fineStatus"] or FINE_CHARGED
        by_status[status] = by_status.get(status, 0) + x["fine"]
        if status == FINE_PENDING:
            pending += x["fine"]
            if not deduct_pending:
                continue
        deducted += x["fine"]
        for p in x["fineParts"]:
            by_type[p["type"]] = by_type.get(p["type"], 0) + p["sum"]
    return {
        "fines": deducted,
        "finesPending": pending,
        "finesByType": [{"type": k, "sum": v} for k, v in sorted(by_type.items(), key=lambda kv: (kv[0] == NO_TYPE, kv[0]))],
        "finesByStatus": [{"status": k, "text": FINE_STATUS_TEXT[k], "sum": v}
                          for k, v in by_status.items()],
    }


def build_report(tasks, date_from, date_to, percent, deduct_pending=True):
    drivers = {}
    in_transit = []
    skipped_outside = skipped_deleted = 0

    for t in tasks if isinstance(tasks, list) else []:
        if not isinstance(t, dict):
            continue
        if t.get("isDeleted") is True:
            skipped_deleted += 1
            continue
        # Закрытые — по дате закрытия, незакрытые — по дате выезда.
        day = day_of(t.get("closeDt") or t.get("openDt"))
        if not day or day < date_from or day > date_to:
            skipped_outside += 1
            continue
        if not t.get("closeDt"):
            in_transit.append({**trip_view(t), "driverName": t.get("driverName") or "—",
                               "driverId": t.get("driverId")})
            continue
        did = t.get("driverId")
        key = f"id:{did}" if did not in (None, "") else f"name:{t.get('driverName') or '—'}"
        d = drivers.setdefault(key, {"driverId": did, "driverName": t.get("driverName") or "—",
                                     "plates": [], "trips": []})
        plate = t.get("vehicleNumberPlate")
        if plate and plate not in d["plates"]:
            d["plates"].append(plate)
        d["trips"].append(trip_view(t))

    rows = []
    for d in drivers.values():
        d["trips"].sort(key=lambda x: str(x["closeDt"]))
        total = sum(x["price"] for x in d["trips"])
        fs = fines_summary(d["trips"], deduct_pending)
        hold = percent_of(total, percent)  # округление по каждому водителю
        rows.append({
            "driverId": d["driverId"], "driverName": d["driverName"], "plates": d["plates"],
            "tripsCount": len(d["trips"]), "sum": total, "retention": hold, **fs,
            "payout": total - hold - fs["fines"],
            "hasFineUnderConsideration": any(x["isFineUnderConsideration"] for x in d["trips"]),
            "trips": d["trips"],
        })
    rows.sort(key=lambda r: r["driverName"].lower())
    in_transit.sort(key=lambda x: str(x["openDt"]))

    # Итог — сумма уже округлённых значений по водителям.
    totals = {k: sum(r[k] for r in rows)
              for k in ("tripsCount", "sum", "retention", "fines", "finesPending", "payout")}
    return {
        "from": date_from, "to": date_to, "percent": percent, "deductPending": deduct_pending,
        "drivers": rows, "totals": totals, "inTransit": in_transit,
        "stats": {"received": len(tasks) if isinstance(tasks, list) else 0,
                  "skippedOutside": skipped_outside, "skippedDeleted": skipped_deleted},
    }


# ---------------------------------------------------------------- ключ

JWT_RE = re.compile(r"^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*$")


def extract_token(text):
    """Ключ из адреса страницы ol.wildberries.ru (bearer=...), строки 'Bearer ...' или сам ключ."""
    if not isinstance(text, str):
        return None
    s = text.strip()
    m = re.search(r"(?:^|[?&#])bearer=([^&#\s]+)", s, re.I)
    if m:
        from urllib.parse import unquote
        s = unquote(m.group(1))
    s = re.sub(r"^bearer\s+", "", s, flags=re.I).strip()
    return s if JWT_RE.match(s) else None


def jwt_payload(token):
    try:
        part = token.split(".")[1]
        return json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))
    except (ValueError, IndexError):
        return None


def key_info(token, now=None):
    """Что можно показать в браузере — без самого ключа."""
    if not token:
        return {"configured": False}
    now = time.time() if now is None else now
    p = jwt_payload(token) or {}
    exp = p.get("exp") if isinstance(p.get("exp"), (int, float)) else None
    return {
        "configured": True,
        "expiresAt": datetime.fromtimestamp(exp, timezone.utc).isoformat() if exp else None,
        "expired": bool(exp and exp <= now),
        "daysLeft": int((exp - now) // 86400) if exp else None,
    }


class KeyStore:
    """Ключ WB на диске только в зашифрованном виде.

    Windows: DPAPI — расшифровать может только эта учётная запись Windows,
    ничего ставить не нужно. Другие системы: AES-GCM из пакета cryptography,
    мастер-ключ из WB_KEY_SECRET или data/master.key.
    """

    def __init__(self, data_dir, secret=None, filename="wb-key.enc"):
        self.dir = Path(data_dir)
        self.file = self.dir / filename
        self.secret = secret if secret is not None else os.environ.get("WB_KEY_SECRET")
        self._cached = ...

    # --- Windows DPAPI
    @staticmethod
    def _dpapi(data, protect):
        import ctypes
        from ctypes import wintypes

        class BLOB(ctypes.Structure):
            _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_char))]

        buf = ctypes.create_string_buffer(data, len(data))
        blob_in = BLOB(len(data), ctypes.cast(buf, ctypes.POINTER(ctypes.c_char)))
        blob_out = BLOB()
        fn = ctypes.windll.crypt32.CryptProtectData if protect else ctypes.windll.crypt32.CryptUnprotectData
        if not fn(ctypes.byref(blob_in), None, None, None, None, 0, ctypes.byref(blob_out)):
            raise OSError("DPAPI: не удалось обработать ключ")
        try:
            return ctypes.string_at(blob_out.pbData, blob_out.cbData)
        finally:
            ctypes.windll.kernel32.LocalFree(blob_out.pbData)

    # --- AES-GCM
    def _master_key(self):
        if self.secret:
            return hashlib.scrypt(self.secret.encode(), salt=b"wb-driver-salary/v1", n=2 ** 14, r=8, p=1, dklen=32)
        f = self.dir / "master.key"
        if f.exists():
            k = base64.b64decode(f.read_text().strip())
            if len(k) == 32:
                return k
        k = os.urandom(32)
        f.write_text(base64.b64encode(k).decode())
        os.chmod(f, 0o600)
        return k

    def _use_dpapi(self):
        return sys.platform == "win32" and not self.secret

    def save(self, token):
        self.dir.mkdir(parents=True, exist_ok=True)
        if self._use_dpapi():
            payload = {"v": 1, "alg": "dpapi", "data": base64.b64encode(self._dpapi(token.encode(), True)).decode()}
        else:
            try:
                from cryptography.hazmat.primitives.ciphers.aead import AESGCM
            except ImportError:
                raise RuntimeError("Для шифрования ключа установите пакет: pip install cryptography")
            iv = os.urandom(12)
            data = AESGCM(self._master_key()).encrypt(iv, token.encode(), None)
            payload = {"v": 1, "alg": "aes-256-gcm", "iv": base64.b64encode(iv).decode(),
                       "data": base64.b64encode(data).decode()}
        tmp = self.file.with_suffix(".tmp")
        tmp.write_text(json.dumps(payload))
        if sys.platform != "win32":
            os.chmod(tmp, 0o600)
        os.replace(tmp, self.file)
        self._cached = token

    def load(self):
        if self._cached is not ...:
            return self._cached
        if not self.file.exists():
            self._cached = None
            return None
        payload = json.loads(self.file.read_text())
        data = base64.b64decode(payload["data"])
        if payload.get("alg") == "dpapi":
            token = self._dpapi(data, False).decode()
        else:
            from cryptography.hazmat.primitives.ciphers.aead import AESGCM
            token = AESGCM(self._master_key()).decrypt(base64.b64decode(payload["iv"]), data, None).decode()
        self._cached = token
        return token

    def remove(self):
        if self.file.exists():
            self.file.unlink()
        self._cached = None


# ---------------------------------------------------------------- WB

class WbError(Exception):
    def __init__(self, code, message, status=None):
        super().__init__(message)
        self.code = code
        self.status = status


def _snippet(raw):
    """Начало ответа WB для диагностики; всё похожее на ключ вырезаем."""
    text = raw.decode("utf-8", "replace")
    text = re.sub(r"<[^>]*>", " ", text)
    text = re.sub(r"[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]*", "[скрыто]", text)
    return re.sub(r"\s+", " ", text).strip()[:200]


def fetch(date_from, date_to, token):
    body = json.dumps({
        "from": date_from, "to": date_to, "isNewMotivation": True,
        "isNotConfirmedWithTransportRequests": None, "officeId": None,
        "paymentTypeId": None, "routeId": None, "supplierId": None,
    }).encode()
    req = urllib.request.Request(URL, data=body, method="POST", headers={
        "Authorization": "Bearer " + token,
        "Content-Type": "application/json;charset=utf-8",
        "Accept": "application/json, text/plain, */*",
        "Origin": "https://ol.wildberries.ru",
        "Referer": "https://ol.wildberries.ru/",
    })
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read()
    except urllib.error.HTTPError as e:
        detail = _snippet(e.read() or b"")
        tail = f" (WB ответил {e.code}{': ' + detail if detail else ''})"
        if e.code in (401, 403):
            print(f"WB ответил {e.code}{': ' + detail if detail else ''}", file=sys.stderr)
            raise WbError("KEY_EXPIRED", "Ключ устарел, обновите." + tail, e.code)
        if e.code == 429:
            raise WbError("WB_RATE_LIMIT", "WB ограничил частоту запросов, попробуйте через пару минут.", 429)
        raise WbError("WB_HTTP", f"Ошибка сервера WB: {e.code}.", e.code)
    except (urllib.error.URLError, TimeoutError, OSError):
        raise WbError("WB_NETWORK", "Нет связи с WB. Проверьте интернет. Если сайт запущен на хостинге, "
                                    "WB может не пускать такие запросы — запустите на домашнем компьютере.")
    try:
        data = json.loads(raw)
    except ValueError:
        raise WbError("WB_FORMAT", "WB вернул не JSON — возможно, API изменился.")
    if isinstance(data, dict):
        data = next((data[k] for k in ("data", "items", "result") if isinstance(data.get(k), list)), None)
    if not isinstance(data, list):
        raise WbError("WB_FORMAT", "Неожиданный формат ответа WB — возможно, API изменился.")
    return data


class WaysheetCache:
    """Один и тот же период одного кабинета запрашиваем у WB не чаще раза в минуту.

    Запросы к одному кабинету идут по очереди; параллельные запросы того же
    периода ждут один общий ответ.
    """

    def __init__(self, fetcher=None):
        self.fetcher = fetcher or fetch
        self.lock = threading.Lock()
        self.entries = {}  # (cabinet, from, to) -> {"at", "lock", "result" | "error"}
        self.queues = {}  # cabinet -> Lock
        self.last_success = {}  # cabinet -> ISO-время последней удачной загрузки

    def clear(self, cabinet=None):
        with self.lock:
            for k in [k for k in self.entries if cabinet is None or k[0] == cabinet]:
                del self.entries[k]

    def get(self, cabinet, token, date_from, date_to):
        key = (cabinet, date_from, date_to)
        with self.lock:
            now = time.time()
            for k in [k for k, v in self.entries.items() if now - v["at"] > CACHE_TTL]:
                del self.entries[k]
            entry = self.entries.get(key)
            if entry is None:
                entry = {"at": now, "lock": threading.Lock()}
                self.entries[key] = entry
            queue = self.queues.setdefault(cabinet, threading.Lock())
        with entry["lock"]:
            if "result" in entry:
                return entry["result"]
            if "error" in entry:
                raise entry["error"]
            if not token:
                with self.lock:
                    self.entries.pop(key, None)
                raise WbError("NO_KEY", "Ключ WB для этого кабинета не задан. Добавьте его в настройках.")
            with queue:
                try:
                    tasks = self.fetcher(date_from, date_to, token)
                except WbError as e:
                    entry["error"] = e
                    raise
            fetched_at = datetime.now(timezone.utc).isoformat()
            self.last_success[cabinet] = fetched_at
            entry["result"] = (tasks, fetched_at)
            return entry["result"]
