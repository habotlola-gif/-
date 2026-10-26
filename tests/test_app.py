import base64
import json
import os
import shutil
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import app  # noqa: E402
import core  # noqa: E402


def b64(o):
    return base64.urlsafe_b64encode(json.dumps(o).encode()).decode().rstrip("=")


def make_jwt(exp):
    return f"{b64({'alg': 'HS256'})}.{b64({'exp': exp})}.c2lnbmF0dXJl"


def make_xlsx(rows):
    """Минимальный xlsx без сторонних пакетов: строки — inlineStr, числа — как есть."""
    import io
    import zipfile
    from xml.sax.saxutils import escape

    def cell(v):
        if isinstance(v, (int, float)):
            return f"<c><v>{v}</v></c>"
        return f'<c t="inlineStr"><is><t>{escape(str(v))}</t></is></c>'

    sheet = ('<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>'
             + "".join("<row>" + "".join(cell(v) for v in r) + "</row>" for r in rows)
             + "</sheetData></worksheet>")
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("xl/worksheets/sheet1.xml", sheet)
    return buf.getvalue()


REMAINS_HEADER = ["ID лог объекта", "Имя лог объекта", "ID маршрута", "Автозаявки", "Время подачи ТС", "Парковка",
                  "Кол-во коробок", "Кол-во КГТ", "Кол-во ШК", "Норма выезда ШК", "Объём (л)",
                  "Среднесуточное накопление, л", "Перевозчики", "Дистанция в сторону, км"]


def routecar(rid, shk, parking, supplier=("777", "ИП Тестов Тест")):
    """Строка ответа WB by-routecar (формат как в настоящем ответе)."""
    return {"routecar": {"id": str(rid), "src": {"office": {"id": 1, "name": "СЦ Тестовый"}},
                         "auto_request": {"enabled": False, "requests": []}, "parking_numbers": [parking],
                         "count_tares": "50", "count_kgt": "26", "count_shk": str(shk), "normative_count_shk": "1132",
                         "total_volume": "4525552", "average_daily_volume": "445113069",
                         "to_normative_shk_percentage": 125, "direct_distance": 32193,
                         "suppliers": [{"id": supplier[0], "name": supplier[1]}]}}


def pretension(pid, waysheet, amount, kind=2, reason="Сколы/Трещины", dt="2026-10-02T10:00:00Z", refund=None):
    """Строка ответа WB pretensions/page (формат как в настоящем ответе)."""
    comment = (f"Путевой лист №{waysheet} ({reason})" if kind == 2 else
               f"Недоставленные коробки / Путевой лист: {waysheet} / Дата отгрузки: 2026-10-01 / Возвратная коробка: Да")
    return {"pretension_id": str(pid), "shk_id": str(pid * 10), "create_dt": dt, "amount": {"value": str(amount)},
            "comment": comment, "is_deleted": False, "pret_type_id": kind, "rop_id": "1", "rop_create_dt": dt,
            "status_id": 3, "office_id": "1", "office_name": "СЦ Тестовый", "rop_refund_dt": refund,
            "operation_type": "CARRIER_PRETENSION"}


def trip(**over):
    t = {"id": 1, "driverName": "Иванов И.", "driverId": 1, "vehicleNumberPlate": "А001АА777",
         "openDt": "2026-10-25T08:00:00", "closeDt": "2026-10-25T18:00:00",
         "totalPrice": 1000, "fineSum": None, "isDeleted": False}
    t.update(over)
    return t


class MoneyTest(unittest.TestCase):
    def test_kopecks(self):
        self.assertEqual(core.kopecks(1234.56), 123456)
        self.assertEqual(core.kopecks(0.29), 29)
        self.assertEqual(core.kopecks(None), 0)
        self.assertEqual(core.kopecks("1 234,5"), 123450)
        self.assertEqual(core.kopecks({"source": "2 767,87", "parsedValue": 2767.87}), 276787)
        self.assertEqual(core.kopecks({"source": "15,10", "parsedValue": None}), 1510)
        self.assertEqual(core.kopecks({"source": "9,99", "parsedValue": "мусор"}), 999)
        self.assertEqual(core.kopecks(-12.34), -1234)

    def test_percent(self):
        self.assertEqual(core.percent_of(8161931, 20), 1632386)  # 16 323,862
        self.assertEqual(core.percent_of(10, 25), 3)  # 2,5 → 3
        self.assertEqual(core.percent_of(12, 12.5), 2)  # 1,5 → 2
        self.assertEqual(core.parse_percent("12,5"), 12.5)
        self.assertIsNone(core.parse_percent("150"))
        self.assertIsNone(core.parse_percent("abc"))


class CalcTest(unittest.TestCase):
    def test_per_driver(self):
        trips = [
            trip(totalPrice=1000.01),
            trip(totalPrice={"source": "500,50", "parsedValue": 500.5}, fineSum=100),
            trip(totalPrice=777, isDeleted=True),
            trip(totalPrice=333, closeDt=None),
            trip(driverId=2, driverName="Петров П.", totalPrice=200, fineSum=10.5),
        ]
        r = core.build_report(trips, "2026-10-25", "2026-10-25", 20)
        self.assertEqual(len(r["drivers"]), 2)
        iv = next(d for d in r["drivers"] if d["driverId"] == 1)
        self.assertEqual((iv["tripsCount"], iv["sum"], iv["retention"], iv["fines"]), (2, 150051, 30010, 10000))
        self.assertEqual(iv["payout"], 150051 - 30010 - 10000)
        self.assertEqual(len(r["inTransit"]), 1)
        self.assertEqual(r["stats"]["skippedDeleted"], 1)

    def test_total_is_sum_of_rounded(self):
        trips = [trip(driverId=i, driverName=f"В{i}", totalPrice=0.03) for i in (1, 2, 3)]
        r = core.build_report(trips, "2026-10-25", "2026-10-25", 20)
        self.assertEqual((r["totals"]["sum"], r["totals"]["retention"], r["totals"]["payout"]), (9, 3, 6))

    def test_period_bounds(self):
        trips = [
            trip(openDt="2026-10-24T22:00:00", closeDt="2026-10-25T02:00:00", totalPrice=1),
            trip(openDt="2026-10-25T22:00:00", closeDt="2026-10-26T02:00:00", totalPrice=2),
            trip(openDt="2026-10-25T23:00:00", closeDt=None, totalPrice=3),
            trip(openDt="2026-10-26T01:00:00", closeDt=None, totalPrice=4),
        ]
        r = core.build_report(trips, "2026-10-25", "2026-10-25", 20)
        self.assertEqual(r["totals"]["sum"], 100)
        self.assertEqual([t["price"] for t in r["inTransit"]], [300])

    def test_day_of(self):
        self.assertEqual(core.day_of("2026-10-25T23:59:59.1234567"), "2026-10-25")
        self.assertEqual(core.day_of("2026-10-25T21:30:00Z"), "2026-10-26")
        self.assertEqual(core.day_of("2026-10-25T21:30:00+03:00"), "2026-10-25")
        self.assertIsNone(core.day_of(None))

    def test_route(self):
        self.assertEqual(core.parse_route("СЦ Подольск (123), ПВЗ Ленина 5 (456)"),
                         [{"name": "СЦ Подольск", "id": "123"}, {"name": "ПВЗ Ленина 5", "id": "456"}])


class FineTest(unittest.TestCase):
    def test_status(self):
        self.assertIsNone(core.fine_status(trip()))
        self.assertEqual(core.fine_status(trip(fineSum=10, isFineUnderConsideration=True, isApproved=True)), "pending")
        self.assertEqual(core.fine_status(trip(fineSum=10, isApproved=True)), "approved")
        self.assertEqual(core.fine_status(trip(fineSum=10, isApproved=False)), "unconfirmed")
        self.assertEqual(core.fine_status(trip(fineSum=10)), "charged")
        self.assertEqual(core.fine_status(trip(isFine=True)), "charged")

    def test_parts(self):
        self.assertEqual(core.fine_parts(trip(fineSum=100)), [{"type": "Без вида", "sum": 10000}])
        self.assertEqual(core.fine_parts(trip(fineSum=100, fineTypeName="Брак")), [{"type": "Брак", "sum": 10000}])
        listed = trip(fineSum=1000, fines=[{"typeName": "Штраф за брак", "sum": 700},
                                           {"name": "Опоздание", "amount": "200,50"}])
        self.assertEqual(core.fine_parts(listed), [{"type": "Штраф за брак", "sum": 70000},
                                                   {"type": "Опоздание", "sum": 20050},
                                                   {"type": "Без вида", "sum": 9950}])
        # Список больше fineSum — не верим списку, главная сумма — fineSum.
        self.assertEqual(core.fine_parts(trip(fineSum=10, fines=[{"name": "X", "sum": 50}])),
                         [{"type": "Без вида", "sum": 1000}])
        self.assertEqual(core.fine_parts(trip(fineSum=None, fines=[{"name": "X", "sum": 5}])),
                         [{"type": "X", "sum": 500}])

    def test_pending_not_deducted(self):
        trips = [trip(totalPrice=1000, fineSum=100, isApproved=True, fineTypeName="Штраф за брак"),
                 trip(totalPrice=1000, fineSum=50, isFineUnderConsideration=True)]
        r = core.build_report(trips, "2026-10-25", "2026-10-25", 20, deduct_pending=False)
        d = r["drivers"][0]
        self.assertEqual((d["fines"], d["finesPending"], d["payout"]), (10000, 5000, 200000 - 40000 - 10000))
        self.assertEqual(d["finesByType"], [{"type": "Штраф за брак", "sum": 10000}])
        r = core.build_report(trips, "2026-10-25", "2026-10-25", 20)
        self.assertEqual(r["drivers"][0]["fines"], 15000)
        text = app.report_text("Иванов", "2026-10-25", "2026-10-25", r["drivers"][0], 20)
        self.assertIn("Штраф за брак всего 100,00", text)
        self.assertIn("Штраф без указания вида всего 50,00", text)
        self.assertIn("В т.ч. на рассмотрении: 50,00", text)


class RemainsTest(unittest.TestCase):
    def test_parse(self):
        import remains
        data = make_xlsx([REMAINS_HEADER,
                          [210515, "СЦ Вёшки", 42225, "нет", "", "36", 50, 26, 1415, 1132, 4525.552, 443560.526,
                           "10105118 ИП Казакбиев Джамалутдин Абдулгамидович", 32.193]])
        items = remains.parse_remains(data, "Остатки.xlsx")
        self.assertEqual(len(items), 1)
        i = items[0]
        self.assertEqual((i["routeId"], i["parking"], i["boxes"], i["kgt"], i["shk"], i["shkNorm"]),
                         (42225, "36", 50, 26, 1415, 1132))
        self.assertEqual(i["carrierList"], [{"id": "10105118", "name": "ИП Казакбиев Джамалутдин Абдулгамидович"}])
        with self.assertRaises(remains.RemainsError):
            remains.parse_remains(b"not a zip", "x.xlsx")
        with self.assertRaises(remains.RemainsError):
            remains.parse_remains(make_xlsx([["что-то"], [1]]), "x.xlsx")
        csv_items = remains.parse_remains("ID маршрута;Кол-во ШК;Парковка\n7;100;5\n".encode(), "o.csv")
        self.assertEqual((csv_items[0]["routeId"], csv_items[0]["shk"]), (7, 100))


class RemainsAutoTest(unittest.TestCase):
    def test_fetch_pages_and_units(self):
        import remains
        calls = []

        def post(url, body, token):
            calls.append(body)
            if body["meta"]["skip"] == 0:
                return {"data": [routecar(1, 1415, 36)], "meta": {"total_items": 2, "has_next": True}}
            return {"data": [routecar(2, 729, 226)], "meta": {"total_items": 2, "has_next": False}}

        items = remains.fetch_remains("tok", ["777"], post)
        self.assertEqual([i["routeId"] for i in items], [1, 2])
        self.assertEqual(calls[0]["data"]["supplier_ids"], [777])
        self.assertEqual([c["meta"]["skip"] for c in calls], [0, 50])
        i = items[0]
        self.assertEqual((i["parking"], i["boxes"], i["kgt"], i["shk"], i["shkNorm"], i["volume"], i["distance"]),
                         ("36", 50, 26, 1415, 1132, 4525.552, 32.193))
        self.assertEqual(i["carrierList"], [{"id": "777", "name": "ИП Тестов Тест"}])
        with self.assertRaises(core.WbError):
            remains.fetch_remains("tok", ["777"], lambda u, b, t: {"error": "x"})


class HoldsTest(unittest.TestCase):
    def test_parse_and_pages(self):
        import holds
        self.assertEqual(holds.parse_comment("Путевой лист №31246587 (Упаковка - неотъемлемая часть товара нарушена)"),
                         {"waysheetId": "31246587", "kind": None, "reason": "Упаковка - неотъемлемая часть товара нарушена",
                          "shipDate": None, "returnBox": None})
        c = holds.parse_comment("Недоставленные коробки / Путевой лист: 31224066 / Дата отгрузки: 2026-09-13 / Возвратная коробка: Да")
        self.assertEqual((c["waysheetId"], c["kind"], c["shipDate"], c["returnBox"]),
                         ("31224066", "Недоставленные коробки", "2026-09-13", "Да"))
        pages = [{"pretensions": [pretension(1, 5, "72", dt="2026-09-16T23:22:26Z")], "next_page_token": "tok2"},
                 {"pretensions": [pretension(2, 6, "671.79", kind=3)], "next_page_token": ""}]
        calls = []

        def post(url, body, token):
            calls.append(body)
            return pages[len(calls) - 1]

        items = holds.fetch_pretensions("t", "777", "2026-09-17", "2026-10-26", post)
        self.assertEqual(calls[0]["date_from"], "2026-09-17T00:00:00+03:00")
        self.assertEqual(calls[1]["page_token"], "tok2")
        self.assertEqual([(i["typeName"], i["amount"], i["waysheetId"], i["day"]) for i in items],
                         [("Брак", 7200, "5", "2026-09-17"), ("Недоставленные коробки", 67179, "6", "2026-10-02")])
        bal = holds.fetch_balance("t", "777", lambda u, t: {"data": {"sum": {"value": "241853.1"},
                                  "sum_available": {"value": "0"}, "sum_fine": {"value": "-18204.68"}, "sum_all": {"value": "1"}}})
        self.assertEqual((bal["sum"], bal["fines"]), (24185310, -1820468))


class KeyTest(unittest.TestCase):
    def test_extract(self):
        jwt = make_jwt(2000000000)
        self.assertEqual(core.extract_token(f"https://ol.wildberries.ru/x?a=1&bearer={jwt}&b=2"), jwt)
        self.assertEqual(core.extract_token(f"https://ol.wildberries.ru/#/p?bearer={jwt}"), jwt)
        self.assertEqual(core.extract_token(f"Bearer {jwt}"), jwt)
        self.assertEqual(core.extract_token(f"  {jwt} "), jwt)
        self.assertIsNone(core.extract_token("https://ol.wildberries.ru/"))

    def test_key_info(self):
        now = 1793000000
        info = core.key_info(make_jwt(now + 10 * 86400), now)
        self.assertEqual((info["expired"], info["daysLeft"]), (False, 10))
        self.assertNotIn("c2lnbmF0dXJl", json.dumps(info))
        self.assertTrue(core.key_info(make_jwt(now - 1), now)["expired"])

    @unittest.skipIf(sys.platform == "win32", "на Windows ключ шифрует DPAPI")
    def test_store_encrypted(self):
        try:
            import cryptography  # noqa: F401
        except ImportError:
            self.skipTest("нет пакета cryptography")
        d = tempfile.mkdtemp()
        try:
            jwt = make_jwt(2000000000)
            core.KeyStore(d, "secret").save(jwt)
            raw = Path(d, "wb-key.enc").read_text()
            self.assertNotIn(jwt.split(".")[1], raw)
            self.assertEqual(core.KeyStore(d, "secret").load(), jwt)
            with self.assertRaises(Exception):
                core.KeyStore(d, "wrong").load()
        finally:
            shutil.rmtree(d)


class ServerTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.calls = []
        self.status = 200

        def fake_fetch(date_from, date_to, token):
            self.calls.append((date_from, date_to, token))
            if self.status != 200:
                raise core.WbError("KEY_EXPIRED", "Ключ устарел, обновите. (WB ответил 401)", 401)
            return [
                # Отчёт с видео: 01.10–05.10, Эмиров, 62 011,11 / штрафы 3 934,25 → 45 674,64
                trip(id=11, driverId=7, driverName="Эмиров Нурали Ханахмедович", totalPrice=30000,
                     fineSum=3056.60, closeDt="2026-10-02T18:00:00"),
                trip(id=12, driverId=7, driverName="Эмиров  Нурали Ханахмедович",
                     totalPrice={"source": "32011,11", "parsedValue": 32011.11}, fineSum=877.65,
                     closeDt="2026-10-05T23:00:00"),
                trip(id=13, driverId=7, driverName="Эмиров Нурали Ханахмедович", totalPrice=999,
                     closeDt="2026-10-06T01:00:00"),
                trip(id=14, driverId=8, driverName="Петров Пётр", totalPrice=500, fineSum=10,
                     closeDt="2026-10-03T12:00:00"),
                trip(id=15, driverId=7, driverName="Эмиров Нурали Ханахмедович", totalPrice=100,
                     openDt="2026-10-03T08:00:00", closeDt=None),
            ]

        self.holds = []  # удержания, которые «вернёт» WB

        def fake_post(url, body, token):
            if url.endswith("/pretensions/page"):
                return {"pretensions": self.holds, "next_page_token": ""}
            return {"data": [], "meta": {"has_next": False}}

        def fake_get(url, token):
            return {"data": {"sum": {"value": "100"}, "sum_available": {"value": "0"},
                             "sum_fine": {"value": "-5.5"}, "sum_all": {"value": "105.5"}}}

        self.app = app.App(self.dir, fake_fetch, fake_post, fake_get)
        self.server = app.make_server("127.0.0.1", 0, self.app)
        self.base = f"http://127.0.0.1:{self.server.server_address[1]}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        shutil.rmtree(self.dir)

    def client(self):
        import http.cookiejar
        return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def req(self, opener, path, method="GET", body=None, headers=None):
        data = json.dumps(body).encode() if body is not None else None
        r = urllib.request.Request(self.base + path, data=data, method=method,
                                   headers={"Content-Type": "application/json", **(headers or {})})
        try:
            with opener.open(r) as resp:
                raw = resp.read()
                return resp.status, json.loads(raw) if raw[:1] in (b"{", b"[") else raw
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    def setup_admin(self):
        admin = self.client()
        status, _ = self.req(admin, "/api/setup", "POST", {"name": "Админ", "login": "admin", "password": "secret1"})
        self.assertEqual(status, 200)
        jwt = make_jwt(2000000000)
        status, cab = self.req(admin, "/api/cabinets", "POST",
                               {"name": "ИП Рахимов Баходур", "value": f"https://ol.wildberries.ru/?bearer={jwt}"})
        self.assertEqual(status, 200)
        self.assertNotIn(jwt.split(".")[2], json.dumps(cab))
        return admin, cab["id"], jwt

    def test_setup_login_and_driver_report(self):
        anon = self.client()
        self.assertEqual(self.req(anon, "/api/me")[1], {"user": None, "needsSetup": True})
        admin, cid, jwt = self.setup_admin()
        self.assertEqual(self.req(anon, "/api/setup", "POST", {"name": "x", "login": "xxx", "password": "123456"})[0], 403)
        self.assertEqual(self.req(anon, "/api/report?from=2026-10-01&to=2026-10-05")[0], 401)

        status, _ = self.req(admin, "/api/users", "POST", {
            "name": "Эмиров Нурали Ханахмедович", "login": "emirov", "password": "pass123", "role": "driver"})
        self.assertEqual(status, 200)

        driver = self.client()
        self.assertEqual(self.req(driver, "/api/login", "POST", {"login": "emirov", "password": "bad"})[0], 401)
        self.assertEqual(self.req(driver, "/api/login", "POST", {"login": "Emirov", "password": "pass123"})[0], 200)

        status, r = self.req(driver, "/api/report?from=2026-10-01&to=2026-10-05")
        self.assertEqual(status, 200)
        self.assertIn(("2026-10-01", "2026-10-06", jwt), self.calls)
        self.assertEqual((r["tripsCount"], r["sum"], r["retention"], r["fines"], r["payout"]),
                         (2, 6201111, 1240222, 393425, 4567464))
        self.assertIn("Минус удержание (20%): 12 402,22", r["text"])
        self.assertIn("Прибыль: 45 674,64", r["text"])
        self.assertEqual(r["inTransit"], 1)

        # Водитель видит только свои путевые и не может смотреть чужие отчёты и настройки.
        status, w = self.req(driver, f"/api/waysheets?date=2026-10-03&cabinet={cid}")
        self.assertEqual(status, 200)
        self.assertEqual([x["id"] for x in w["items"]], [15])
        status, w = self.req(admin, f"/api/waysheets?date=2026-10-03&cabinet={cid}")
        self.assertEqual(sorted(x["id"] for x in w["items"]), [14, 15])
        self.assertEqual(self.req(driver, "/api/users")[0], 403)
        self.assertEqual(self.req(driver, "/api/summary?from=2026-10-01&to=2026-10-05")[0], 403)
        admin_id = self.req(admin, "/api/me")[1]["user"]["id"]
        self.assertEqual(self.req(driver, f"/api/report?from=2026-10-01&to=2026-10-05&employee={admin_id}")[0], 403)

        # Администратор смотрит отчёт водителя из WB, не заведённого в сотрудники; водителю так нельзя.
        status, r = self.req(admin, "/api/report?from=2026-10-01&to=2026-10-05&driver=" + urllib.parse.quote("Петров Пётр"))
        self.assertEqual((status, r["tripsCount"], r["sum"], r["fines"]), (200, 1, 50000, 1000))
        self.assertIn("Зарплата партнера: Петров Пётр", r["text"])
        status, r = self.req(admin, "/api/report?from=2026-10-01&to=2026-10-05&driver=x&driverId=8")
        self.assertEqual(r["tripsCount"], 1)
        self.assertEqual(self.req(driver, "/api/report?from=2026-10-01&to=2026-10-05&driver=" + urllib.parse.quote("Петров Пётр"))[0], 403)

        # Удержания водителя — только его штрафы.
        status, f = self.req(driver, "/api/fines?from=2026-10-01&to=2026-10-05")
        self.assertEqual((status, f["total"]), (200, 393425))

        # Ведомость и кеш: другой процент — без нового запроса к WB.
        n = len(self.calls)
        status, s = self.req(admin, "/api/summary?from=2026-10-01&to=2026-10-05&percent=20")
        self.assertEqual((status, s["totals"]["sum"]), (200, 6201111 + 50000))
        self.assertEqual(len(self.calls), n)

    def test_personal_percent_and_expired_key(self):
        admin, cid, _ = self.setup_admin()
        _, u = self.req(admin, "/api/users", "POST", {
            "name": "Иван", "login": "ivan", "password": "pass123", "driverName": "Эмиров Нурали Ханахмедович",
            "percent": "25"})
        status, r = self.req(admin, f"/api/report?from=2026-10-01&to=2026-10-05&employee={u['id']}")
        self.assertEqual((status, r["percent"], r["retention"]), (200, 25, 1550278))

        self.app.cache.clear()
        self.status = 401
        status, body = self.req(admin, "/api/report?from=2026-10-01&to=2026-10-05")
        self.assertEqual((status, body["error"]["code"]), (409, "KEY_EXPIRED"))
        self.assertIn("устарел", body["error"]["message"])

    def test_validation_and_origin(self):
        admin, cid, _ = self.setup_admin()
        self.assertEqual(self.req(admin, "/api/report?from=2026-13-01")[0], 400)
        self.assertEqual(self.req(admin, "/api/report?from=2026-10-26&to=2026-10-25")[0], 400)
        self.assertEqual(self.req(admin, f"/api/cabinets/{cid}", "DELETE",
                                  headers={"Origin": "https://evil.example"})[0], 403)
        self.assertEqual(self.req(admin, "/api/users", "POST", {"name": "a", "login": "admin", "password": "123456"})[0], 400)

    def test_old_key_migrates_to_cabinet(self):
        d = tempfile.mkdtemp()
        try:
            Path(d, "wb-key.enc").write_text("{}")
            a = app.App(d)
            self.assertEqual([c["name"] for c in a.db.cabinets()], ["Кабинет 1"])
            self.assertTrue(Path(d, f"wb-key-{a.db.cabinets()[0]['id']}.enc").exists())
        finally:
            shutil.rmtree(d)

    def test_remains_upload_and_parking(self):
        admin, cid, _ = self.setup_admin()
        rows = [REMAINS_HEADER,
                [1, "СЦ Вёшки", 42225, "нет", "", "62", 50, 26, 1415, 1132, 1.5, 2.5, "1 ИП А", 3.2],
                [1, "СЦ Вёшки", 99, "нет", "", "7", 1, 0, 10, 20, 1, 1, "2 ИП Б", 1]]
        r = urllib.request.Request(self.base + "/api/remains?name=o.xlsx", data=make_xlsx(rows), method="POST",
                                   headers={"Content-Type": "application/octet-stream"})
        with admin.open(r) as resp:
            self.assertEqual(json.loads(resp.read())["loaded"], 2)
        status, body = self.req(admin, "/api/remains")
        self.assertEqual((status, [i["routeId"] for i in body["items"]], body["carriers"]), (200, [42225, 99], ["ИП А", "ИП Б"]))
        # Повторная выгрузка по «ИП А» заменяет только его маршруты.
        r = urllib.request.Request(self.base + "/api/remains?name=o.xlsx", method="POST",
                                   data=make_xlsx([REMAINS_HEADER, [1, "СЦ", 5, "", "", "1", 0, 0, 3, 1, 0, 0, "1 ИП А", 0]]),
                                   headers={"Content-Type": "application/octet-stream"})
        admin.open(r).read()
        self.assertEqual(sorted(i["routeId"] for i in self.req(admin, "/api/remains")[1]["items"]), [5, 99])

        # Парковка в карточке путевого — по ID маршрута из остатков.
        self.app.save_remains([{"routeId": 555, "parking": "62", "carrierList": [{"id": None, "name": "ИП В"}]}], "x", "a")
        self.app.db.data["settings"]["percent"] = 20
        orig = self.app.cache.fetcher
        self.app.cache.fetcher = lambda f, t, tok: [trip(id=1, logRouteId=555, openDt="2026-10-03T08:00:00", closeDt=None)]
        self.app.cache.clear()
        _, w = self.req(admin, f"/api/waysheets?date=2026-10-03&cabinet={cid}")
        self.assertEqual(w["items"][0]["parking"], "62")
        self.app.cache.fetcher = orig

        driver = self.client()
        self.req(admin, "/api/users", "POST", {"name": "Вод", "login": "vod", "password": "pass123"})
        self.req(driver, "/api/login", "POST", {"login": "vod", "password": "pass123"})
        self.assertEqual(self.req(driver, "/api/remains")[0], 200)
        r = urllib.request.Request(self.base + "/api/remains?name=o.xlsx", data=b"x", method="POST",
                                   headers={"Content-Type": "application/octet-stream"})
        with self.assertRaises(urllib.error.HTTPError) as cm:
            driver.open(r)
        self.assertEqual(cm.exception.code, 403)

    def test_remains_auto(self):
        admin, cid, jwt = self.setup_admin()
        posts = []
        state = {"rows": [routecar(10, 1415, 36), routecar(11, 700, 5)]}

        def post(url, body, token):
            posts.append((url, body, token))
            return {"data": state["rows"], "meta": {"has_next": False}}

        self.app.post = post
        # ID поставщика не задан и в путевых его нет — понятная ошибка.
        status, r = self.req(admin, "/api/remains/refresh", "POST", {})
        self.assertEqual(status, 200)
        self.assertIn("ID поставщика", r["auto"][0]["error"])
        self.assertEqual(posts, [])

        self.assertEqual(self.req(admin, f"/api/cabinets/{cid}", "PUT", {"supplierIds": "abc"})[0], 400)
        self.assertEqual(self.req(admin, f"/api/cabinets/{cid}", "PUT", {"supplierIds": "777"})[0], 200)
        status, r = self.req(admin, "/api/remains/refresh", "POST", {})
        self.assertEqual(([i["routeId"] for i in r["items"]], r["auto"][0]["count"], r["auto"][0]["error"]),
                         ([10, 11], 2, None))
        self.assertEqual(posts[0][2], jwt)
        self.assertTrue(posts[0][0].endswith("/last-mile-report/v1/report/by-routecar"))

        # Чаще раза в минуту не спрашиваем.
        self.req(admin, "/api/remains/refresh", "POST", {})
        self.assertEqual(len(posts), 1)

        # Маршрут уехал — пропадает и у нас.
        state["rows"] = [routecar(10, 100, 36)]
        self.app.remains_status[cid]["tried"] -= 120
        _, r = self.req(admin, "/api/remains/refresh", "POST", {})
        self.assertEqual([i["routeId"] for i in r["items"]], [10])
        state["rows"] = []
        self.app.remains_status[cid]["tried"] -= 120
        _, r = self.req(admin, "/api/remains/refresh", "POST", {})
        self.assertEqual(r["items"], [])

        # ID поставщика находится сам по путевым, если WB его там присылает.
        self.req(admin, f"/api/cabinets/{cid}", "PUT", {"supplierIds": ""})
        self.app.cache.fetcher = lambda f, t, tok: [trip(supplierId=888)]
        self.app.cache.clear()
        self.app.refresh_remains(cid, force=True)
        self.assertEqual(posts[-1][1]["data"]["supplier_ids"], [888])
        self.assertEqual(self.app.db.cabinet(cid)["supplierIds"], ["888"])

        # Отдельный ключ остатков и удержаний — в запросах остатков идёт он, путевые — с основным.
        self.assertEqual(self.req(admin, f"/api/cabinets/{cid}", "PUT", {"value2": "короткий"})[0], 400)
        svc = "opaque-service-token-1234567890"
        status, info = self.req(admin, f"/api/cabinets/{cid}", "PUT", {"value2": f"Bearer {svc}"})
        self.assertEqual((status, info["key2"]["configured"]), (200, True))
        self.assertNotIn(svc, json.dumps(info))
        self.app.refresh_remains(cid, force=True)
        self.assertEqual(posts[-1][2], svc)
        self.assertEqual(self.app.db.token(cid), jwt)

        def denied(url, body, token):
            raise core.WbError("KEY_EXPIRED", "Ключ устарел, обновите. (WB ответил 401)", 401)
        self.app.post = denied
        self.app.remains_status[cid]["tried"] -= 120
        _, r = self.req(admin, "/api/remains/refresh", "POST", {})
        self.assertIn("Ключ остатков и удержаний", r["auto"][0]["error"])
        self.assertEqual(self.req(admin, f"/api/cabinets/{cid}", "PUT", {"clearValue2": True})[1]["key2"]["configured"], False)

        self.assertEqual(self.req(admin, "/api/settings", "POST", {"remainsMinutes": 2})[0], 400)
        self.assertEqual(self.req(admin, "/api/settings", "POST", {"remainsMinutes": 15})[1]["remainsMinutes"], 15)

    def test_holds(self):
        admin, cid, _ = self.setup_admin()
        self.req(admin, f"/api/cabinets/{cid}", "PUT", {"supplierIds": "777"})
        # 11, 12 — путевые Эмирова, 14 — Петрова, 99 — неизвестный путевой.
        self.holds = [pretension(1, 11, "100"), pretension(2, 11, "50"), pretension(3, 14, "20", kind=3),
                      pretension(4, 99, "7"), pretension(5, 12, "30", refund="2026-10-04T00:00:00Z")]
        status, r = self.req(admin, "/api/holds?from=2026-10-01&to=2026-10-05")
        self.assertEqual(status, 200)
        self.assertEqual(r["total"], 17700)  # возвращённое (5) не считается
        self.assertEqual(r["unmatched"], {"count": 1, "sum": 700})
        g = next(x for x in r["groups"] if x["waysheetId"] == "11")
        self.assertEqual((g["count"], g["sum"], g["driverName"], g["typeName"]), (2, 15000, "Эмиров Нурали Ханахмедович", "Брак"))
        self.assertEqual(r["balances"][0]["fines"], -550)

        self.req(admin, "/api/users", "POST", {"name": "Эмиров Нурали Ханахмедович", "login": "emirov", "password": "pass123"})
        driver = self.client()
        self.req(driver, "/api/login", "POST", {"login": "emirov", "password": "pass123"})
        status, r = self.req(driver, "/api/holds?from=2026-10-01&to=2026-10-05")
        self.assertEqual((status, r["total"], "balances" in r), (200, 15000, False))

        # По умолчанию удержания вычитаются из прибыли; в отчёте их можно отключить.
        _, rep = self.req(driver, "/api/report?from=2026-10-01&to=2026-10-05")
        self.assertEqual((rep["holds"], rep["payout"]), (15000, 4567464 - 15000))
        self.assertIn("Удержание WB «Брак» всего 150,00", rep["text"])
        self.assertIn("Минус удержания WB: 150,00", rep["text"])
        self.assertIn("Прибыль: 45 524,64", rep["text"])
        _, rep = self.req(driver, "/api/report?from=2026-10-01&to=2026-10-05&holds=0")
        self.assertEqual((rep["holds"], rep["payout"]), (15000, 4567464))
        self.assertNotIn("Удержание WB", rep["text"])
        self.assertIn("Прибыль: 45 674,64", rep["text"])

    def test_static(self):
        with urllib.request.urlopen(self.base + "/") as r:
            self.assertIn("LogisticaWB", r.read().decode())


if __name__ == "__main__":
    unittest.main()
