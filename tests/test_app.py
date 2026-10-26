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
        self.assertEqual(app.kopecks(1234.56), 123456)
        self.assertEqual(app.kopecks(0.29), 29)
        self.assertEqual(app.kopecks(None), 0)
        self.assertEqual(app.kopecks("1 234,5"), 123450)
        self.assertEqual(app.kopecks({"source": "2 767,87", "parsedValue": 2767.87}), 276787)
        self.assertEqual(app.kopecks({"source": "15,10", "parsedValue": None}), 1510)
        self.assertEqual(app.kopecks({"source": "9,99", "parsedValue": "мусор"}), 999)
        self.assertEqual(app.kopecks(-12.34), -1234)

    def test_percent(self):
        self.assertEqual(app.percent_of(8161931, 20), 1632386)  # 16 323,862
        self.assertEqual(app.percent_of(10, 25), 3)  # 2,5 → 3
        self.assertEqual(app.percent_of(12, 12.5), 2)  # 1,5 → 2
        self.assertEqual(app.parse_percent("12,5"), 12.5)
        self.assertIsNone(app.parse_percent("150"))
        self.assertIsNone(app.parse_percent("abc"))


class CalcTest(unittest.TestCase):
    def test_per_driver(self):
        trips = [
            trip(totalPrice=1000.01),
            trip(totalPrice={"source": "500,50", "parsedValue": 500.5}, fineSum=100),
            trip(totalPrice=777, isDeleted=True),
            trip(totalPrice=333, closeDt=None),
            trip(driverId=2, driverName="Петров П.", totalPrice=200, fineSum=10.5),
        ]
        r = app.build_report(trips, "2026-10-25", "2026-10-25", 20)
        self.assertEqual(len(r["drivers"]), 2)
        iv = next(d for d in r["drivers"] if d["driverId"] == 1)
        self.assertEqual((iv["tripsCount"], iv["sum"], iv["retention"], iv["fines"]), (2, 150051, 30010, 10000))
        self.assertEqual(iv["payout"], 150051 - 30010 - 10000)
        self.assertEqual(len(r["inTransit"]), 1)
        self.assertEqual(r["stats"]["skippedDeleted"], 1)

    def test_total_is_sum_of_rounded(self):
        trips = [trip(driverId=i, driverName=f"В{i}", totalPrice=0.03) for i in (1, 2, 3)]
        r = app.build_report(trips, "2026-10-25", "2026-10-25", 20)
        self.assertEqual((r["totals"]["sum"], r["totals"]["retention"], r["totals"]["payout"]), (9, 3, 6))

    def test_period_bounds(self):
        trips = [
            trip(openDt="2026-10-24T22:00:00", closeDt="2026-10-25T02:00:00", totalPrice=1),
            trip(openDt="2026-10-25T22:00:00", closeDt="2026-10-26T02:00:00", totalPrice=2),
            trip(openDt="2026-10-25T23:00:00", closeDt=None, totalPrice=3),
            trip(openDt="2026-10-26T01:00:00", closeDt=None, totalPrice=4),
        ]
        r = app.build_report(trips, "2026-10-25", "2026-10-25", 20)
        self.assertEqual(r["totals"]["sum"], 100)
        self.assertEqual([t["price"] for t in r["inTransit"]], [300])

    def test_day_of(self):
        self.assertEqual(app.day_of("2026-10-25T23:59:59.1234567"), "2026-10-25")
        self.assertEqual(app.day_of("2026-10-25T21:30:00Z"), "2026-10-26")
        self.assertEqual(app.day_of("2026-10-25T21:30:00+03:00"), "2026-10-25")
        self.assertIsNone(app.day_of(None))

    def test_route(self):
        self.assertEqual(app.parse_route("СЦ Подольск (123), ПВЗ Ленина 5 (456)"),
                         [{"name": "СЦ Подольск", "id": "123"}, {"name": "ПВЗ Ленина 5", "id": "456"}])


class KeyTest(unittest.TestCase):
    def test_extract(self):
        jwt = make_jwt(2000000000)
        self.assertEqual(app.extract_token(f"https://ol.wildberries.ru/x?a=1&bearer={jwt}&b=2"), jwt)
        self.assertEqual(app.extract_token(f"https://ol.wildberries.ru/#/p?bearer={jwt}"), jwt)
        self.assertEqual(app.extract_token(f"Bearer {jwt}"), jwt)
        self.assertEqual(app.extract_token(f"  {jwt} "), jwt)
        self.assertIsNone(app.extract_token("https://ol.wildberries.ru/"))

    def test_key_info(self):
        now = 1793000000
        info = app.key_info(make_jwt(now + 10 * 86400), now)
        self.assertEqual((info["expired"], info["daysLeft"]), (False, 10))
        self.assertNotIn("c2lnbmF0dXJl", json.dumps(info))
        self.assertTrue(app.key_info(make_jwt(now - 1), now)["expired"])

    @unittest.skipIf(sys.platform == "win32", "на Windows ключ шифрует DPAPI")
    def test_store_encrypted(self):
        try:
            import cryptography  # noqa: F401
        except ImportError:
            self.skipTest("нет пакета cryptography")
        d = tempfile.mkdtemp()
        try:
            jwt = make_jwt(2000000000)
            app.KeyStore(d, "secret").save(jwt)
            raw = Path(d, "wb-key.enc").read_text()
            self.assertNotIn(jwt.split(".")[1], raw)
            self.assertEqual(app.KeyStore(d, "secret").load(), jwt)
            with self.assertRaises(Exception):
                app.KeyStore(d, "wrong").load()
        finally:
            shutil.rmtree(d)


class ServerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dir = tempfile.mkdtemp()
        app.DATA_DIR = Path(cls.dir)
        cls.calls = []
        cls.status = 200

        def fake_fetch(date_from, date_to, token):
            cls.calls.append((date_from, date_to, token))
            if cls.status != 200:
                raise app.WbError("KEY_EXPIRED", "Ключ устарел, обновите. (WB ответил 401)", 401)
            return [
                trip(id=1, driverId=7, driverName="Сидоров", totalPrice={"source": "1000", "parsedValue": 1000},
                     fineSum=50),
                trip(id=2, driverId=7, driverName="Сидоров", totalPrice=500,
                     openDt="2026-10-25T20:00:00", closeDt="2026-10-26T01:00:00"),
            ]

        class MemStore:
            token = None

            def save(self, t):
                self.token = t

            def load(self):
                return self.token

            def remove(self):
                self.token = None

        cls.store = MemStore()
        cls.cache = app.WaysheetCache(cls.store, fake_fetch)
        cls.server = app.make_server("127.0.0.1", 0, cls.store, cls.cache)
        cls.base = f"http://127.0.0.1:{cls.server.server_address[1]}"
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        shutil.rmtree(cls.dir)

    def req(self, path, method="GET", body=None, headers=None):
        data = json.dumps(body).encode() if body is not None else None
        r = urllib.request.Request(self.base + path, data=data, method=method,
                                   headers={"Content-Type": "application/json", **(headers or {})})
        try:
            with urllib.request.urlopen(r) as resp:
                return resp.status, json.loads(resp.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    def test_flow(self):
        status, body = self.req("/api/report?from=2026-10-25&to=2026-10-25")
        self.assertEqual((status, body["error"]["code"]), (409, "NO_KEY"))

        jwt = make_jwt(2000000000)
        status, body = self.req("/api/key", "POST", {"value": f"https://ol.wildberries.ru/?bearer={jwt}"})
        self.assertEqual(status, 200)
        self.assertNotIn(jwt.split(".")[2], json.dumps(body))

        self.calls.clear()
        status, r = self.req("/api/report?from=2026-10-25&to=2026-10-25&percent=20")
        self.assertEqual(status, 200)
        self.assertEqual(self.calls, [("2026-10-25", "2026-10-26", jwt)])
        self.assertEqual((r["totals"]["sum"], r["totals"]["retention"], r["totals"]["fines"], r["totals"]["payout"]),
                         (100000, 20000, 5000, 75000))

        # Другой процент — без нового запроса к WB.
        _, r2 = self.req("/api/report?from=2026-10-25&to=2026-10-25&percent=10")
        self.assertEqual(len(self.calls), 1)
        self.assertEqual(r2["totals"]["payout"], 85000)

        self.cache.clear()
        type(self).status = 401
        status, body = self.req("/api/report?from=2026-10-20&to=2026-10-20")
        self.assertEqual((status, body["error"]["code"]), (409, "KEY_EXPIRED"))
        self.assertIn("устарел", body["error"]["message"])
        type(self).status = 200

    def test_validation(self):
        self.assertEqual(self.req("/api/report?from=2026-13-01")[0], 400)
        self.assertEqual(self.req("/api/report?from=2026-10-26&to=2026-10-25")[0], 400)
        self.assertEqual(self.req("/api/report?from=2026-10-25&percent=150")[0], 400)
        self.assertEqual(self.req("/api/key", "DELETE", headers={"Origin": "https://evil.example"})[0], 403)

    def test_static(self):
        with urllib.request.urlopen(self.base + "/") as r:
            self.assertIn("Зарплата водителей", r.read().decode())


if __name__ == "__main__":
    unittest.main()
