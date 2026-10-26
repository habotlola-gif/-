"""Сайт расчёта зарплаты водителей по данным WB Логистики.

Запуск:  python app.py   (или двойной щелчок по start.bat)
Нужен только Python 3.9+, сторонние пакеты на Windows не нужны.

Формула (как в приложении):
    после удержания = сумма заданий - 20%
    водителю = после удержания - штрафы
Все суммы считаются в копейках, целыми числами.
"""
import base64
import hashlib
import hmac
import json
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.request
import webbrowser
from datetime import date, datetime, timedelta, timezone
from decimal import ROUND_HALF_UP, Decimal, InvalidOperation
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

BASE_DIR = Path(__file__).resolve().parent
PUBLIC_DIR = BASE_DIR / "public"
DATA_DIR = Path(os.environ.get("DATA_DIR") or BASE_DIR / "data").resolve()
PORT = int(os.environ.get("PORT") or 3000)
HOST = os.environ.get("HOST") or "127.0.0.1"
APP_PASSWORD = os.environ.get("APP_PASSWORD") or ""
# Москва живёт по UTC+3 без перехода на летнее время.
REPORT_TZ = timezone(timedelta(hours=float(os.environ.get("TZ_OFFSET_HOURS") or 3)))
CACHE_TTL = 60
MAX_PERIOD_DAYS = 93

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


def trip_view(t):
    ret = t.get("totalSumReturn")
    return {
        "id": t.get("id"),
        "openDt": t.get("openDt"),
        "closeDt": t.get("closeDt"),
        "vehicleNumberPlate": t.get("vehicleNumberPlate") or "",
        "price": kopecks(t.get("totalPrice")),
        "fine": kopecks(t.get("fineSum")),
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


def build_report(tasks, date_from, date_to, percent):
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
        fines = sum(x["fine"] for x in d["trips"])
        hold = percent_of(total, percent)  # округление по каждому водителю
        rows.append({
            "driverId": d["driverId"], "driverName": d["driverName"], "plates": d["plates"],
            "tripsCount": len(d["trips"]), "sum": total, "retention": hold, "fines": fines,
            "payout": total - hold - fines,
            "hasFineUnderConsideration": any(x["isFineUnderConsideration"] for x in d["trips"]),
            "trips": d["trips"],
        })
    rows.sort(key=lambda r: r["driverName"].lower())
    in_transit.sort(key=lambda x: str(x["openDt"]))

    # Итог — сумма уже округлённых значений по водителям.
    totals = {k: sum(r[k] for r in rows) for k in ("tripsCount", "sum", "retention", "fines", "payout")}
    return {
        "from": date_from, "to": date_to, "percent": percent,
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

    def __init__(self, data_dir, secret=None):
        self.dir = Path(data_dir)
        self.file = self.dir / "wb-key.enc"
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
    """Один и тот же период запрашиваем у WB не чаще раза в минуту."""

    def __init__(self, store, fetcher=None):
        self.store = store
        self.fetcher = fetcher or fetch
        self.lock = threading.Lock()
        self.entries = {}  # (from, to) -> {"at", "lock", "result" | "error"}

    def clear(self):
        with self.lock:
            self.entries.clear()

    def get(self, date_from, date_to):
        key = (date_from, date_to)
        with self.lock:
            now = time.time()
            for k in [k for k, v in self.entries.items() if now - v["at"] > CACHE_TTL]:
                del self.entries[k]
            entry = self.entries.get(key)
            if entry is None:
                entry = {"at": now, "lock": threading.Lock()}
                self.entries[key] = entry
        with entry["lock"]:  # параллельные запросы ждут один общий ответ
            if "result" in entry:
                return entry["result"]
            if "error" in entry:
                raise entry["error"]
            token = self.store.load()
            if not token:
                with self.lock:
                    self.entries.pop(key, None)
                raise WbError("NO_KEY", "Ключ WB не задан. Вставьте его в настройках.")
            try:
                tasks = self.fetcher(date_from, date_to, token)
            except WbError as e:
                entry["error"] = e
                raise
            entry["result"] = (tasks, datetime.now(timezone.utc).isoformat())
            return entry["result"]


# ---------------------------------------------------------------- настройки

def settings_file():
    return DATA_DIR / "settings.json"


def load_settings():
    s = {"percent": 20}
    try:
        s.update(json.loads(settings_file().read_text(encoding="utf-8")))
    except (OSError, ValueError):
        pass
    return s


def save_settings(s):
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    settings_file().write_text(json.dumps(s, ensure_ascii=False, indent=2), encoding="utf-8")


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


def make_handler(store, cache):
    class Handler(BaseHTTPRequestHandler):
        server_version = "wb-salary"

        def log_message(self, fmt, *args):
            pass  # не пишем запросы в консоль

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

        def error(self, status, code, message):
            self.send(status, {"error": {"code": code, "message": message}})

        def read_json(self):
            n = int(self.headers.get("Content-Length") or 0)
            if n > 64 * 1024:
                raise ValueError("too large")
            return json.loads(self.rfile.read(n) or b"{}")

        def authorized(self):
            if not APP_PASSWORD:
                return True
            m = re.match(r"^Basic\s+(.+)$", self.headers.get("Authorization") or "", re.I)
            if not m:
                return False
            try:
                password = base64.b64decode(m.group(1)).decode().split(":", 1)[1]
            except (ValueError, IndexError):
                return False
            return hmac.compare_digest(password.encode(), APP_PASSWORD.encode())

        def same_origin(self):
            origin = self.headers.get("Origin")
            return not origin or urlparse(origin).netloc == self.headers.get("Host")

        def dispatch(self, method):
            if not self.authorized():
                return self.send(401, "Нужен пароль", {
                    "WWW-Authenticate": 'Basic realm="WB salary", charset="UTF-8"',
                    "Content-Type": "text/plain; charset=utf-8"})
            url = urlparse(self.path)
            path = url.path
            if method == "GET" and path in STATIC:
                name, ctype = STATIC[path]
                return self.send(200, (PUBLIC_DIR / name).read_bytes(),
                                 {"Content-Type": ctype, "Cache-Control": "no-cache"})
            if method != "GET" and not self.same_origin():
                return self.error(403, "FORBIDDEN", "Чужой источник запроса.")

            if path == "/api/report" and method == "GET":
                return self.report(parse_qs(url.query))

            if path == "/api/key" and method == "GET":
                try:
                    return self.send(200, key_info(store.load()))
                except Exception:
                    return self.send(200, {"configured": False, "broken": True})

            if path == "/api/key" and method == "POST":
                try:
                    body = self.read_json()
                except ValueError:
                    return self.error(400, "BAD_JSON", "Неверный запрос.")
                token = extract_token(body.get("value") if isinstance(body, dict) else None)
                if not token:
                    return self.error(400, "BAD_KEY", "Не нашёл ключ. Вставьте адрес страницы "
                                      "ol.wildberries.ru целиком (с «bearer=») или сам ключ.")
                info = key_info(token)
                if info["expired"]:
                    return self.error(400, "KEY_EXPIRED", "Этот ключ уже истёк — возьмите свежий. "
                                      "Если ключ точно свежий, проверьте дату и время на компьютере.")
                try:
                    store.save(token)
                except RuntimeError as e:
                    return self.error(500, "NO_CRYPTO", str(e))
                cache.clear()
                return self.send(200, info)

            if path == "/api/key" and method == "DELETE":
                store.remove()
                cache.clear()
                return self.send(200, {"configured": False})

            if path == "/api/settings" and method == "GET":
                return self.send(200, load_settings())

            if path == "/api/settings" and method == "POST":
                try:
                    body = self.read_json()
                except ValueError:
                    return self.error(400, "BAD_JSON", "Неверный запрос.")
                percent = parse_percent(body.get("percent") if isinstance(body, dict) else None)
                if percent is None:
                    return self.error(400, "BAD_PERCENT", "Процент должен быть от 0 до 100.")
                s = {**load_settings(), "percent": percent}
                save_settings(s)
                return self.send(200, s)

            return self.error(404, "NOT_FOUND", "Не найдено.")

        def report(self, q):
            date_from = (q.get("from") or [None])[0]
            date_to = (q.get("to") or [date_from])[0]
            if not valid_date(date_from) or not valid_date(date_to):
                return self.error(400, "BAD_DATE", "Неверная дата.")
            if date_from > date_to:
                return self.error(400, "BAD_DATE", "Начало периода позже конца.")
            if add_days(date_from, MAX_PERIOD_DAYS) <= date_to:
                return self.error(400, "BAD_DATE", f"Период не длиннее {MAX_PERIOD_DAYS} дней.")
            raw = (q.get("percent") or [None])[0]
            percent = load_settings()["percent"] if raw is None else parse_percent(raw)
            if percent is None:
                return self.error(400, "BAD_PERCENT", "Процент должен быть от 0 до 100.")
            try:
                # WB запрашиваем до следующего дня, затем режем по дате закрытия.
                tasks, fetched_at = cache.get(date_from, add_days(date_to, 1))
            except WbError as e:
                status = 409 if e.code in ("KEY_EXPIRED", "NO_KEY") else 502
                return self.error(status, e.code, str(e))
            rep = build_report(tasks, date_from, date_to, percent)
            self.send(200, {**rep, "fetchedAt": fetched_at, "cacheTtlSec": CACHE_TTL})

        def handle_method(self, method):
            try:
                self.dispatch(method)
            except Exception as e:
                print("Ошибка:", type(e).__name__, file=sys.stderr)
                try:
                    self.error(500, "INTERNAL", "Внутренняя ошибка сервера.")
                except OSError:
                    pass

        def do_GET(self):
            self.handle_method("GET")

        def do_POST(self):
            self.handle_method("POST")

        def do_DELETE(self):
            self.handle_method("DELETE")

    return Handler


class Server(ThreadingHTTPServer):
    # На Windows SO_REUSEADDR позволяет двум программам занять один порт — выключаем.
    allow_reuse_address = sys.platform != "win32"
    daemon_threads = True


def make_server(host, port, store=None, cache=None):
    store = store or KeyStore(DATA_DIR)
    cache = cache or WaysheetCache(store)
    return Server((host, port), make_handler(store, cache))


def main():
    server = None
    port = PORT
    # Если порт занят (например, сайт уже запущен в другом окне), пробуем следующие.
    for port in range(PORT, PORT + 10):
        try:
            server = make_server(HOST, port)
            break
        except OSError:
            print(f"Порт {port} занят, пробую {port + 1}…")
    if server is None:
        sys.exit("Не нашёл свободный порт.")
    url = f"http://{'localhost' if HOST == '0.0.0.0' else HOST}:{port}"
    print(f"\n  Сайт запущен: {url}")
    print("  Не закрывайте это окно, пока пользуетесь сайтом. Остановить: Ctrl+C.\n")
    if HOST not in ("127.0.0.1", "localhost") and not APP_PASSWORD:
        print("Внимание: сайт доступен из сети без пароля. Задайте APP_PASSWORD.")
    if not os.environ.get("NO_BROWSER"):
        threading.Timer(0.5, webbrowser.open, (url,)).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("Остановлено.")


if __name__ == "__main__":
    main()
