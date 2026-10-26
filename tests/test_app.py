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

        self.app = app.App(self.dir, fake_fetch)
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
        self.assertEqual(self.calls[-1], ("2026-10-01", "2026-10-06", jwt))
        self.assertEqual((r["tripsCount"], r["sum"], r["retention"], r["fines"], r["payout"]),
                         (2, 6201111, 1240222, 393425, 4567464))
        self.assertIn("Минус удержание (20%): 12 402,22", r["text"])
        self.assertIn("Итого к выплате водителю: 45 674,64", r["text"])
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

    def test_static(self):
        with urllib.request.urlopen(self.base + "/") as r:
            self.assertIn("LogisticaWB", r.read().decode())


if __name__ == "__main__":
    unittest.main()
