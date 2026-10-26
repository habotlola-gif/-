"""Остатки: разбор выгрузки WB (xlsx или csv) с маршрутами, ШК, коробками и КГТ.

Excel читаем без сторонних пакетов: xlsx — это zip с XML внутри.
"""
import csv
import io
import re
import zipfile
import xml.etree.ElementTree as ET

from core import WbError

NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main",
      "r": "http://schemas.openxmlformats.org/officeDocument/2006/relationships"}
REL_NS = "{http://schemas.openxmlformats.org/package/2006/relationships}"
MAX_XML = 50 * 1024 * 1024  # защита от «zip-бомбы»


class RemainsError(Exception):
    pass


def _col_index(ref, default):
    """Номер колонки из адреса ячейки «C5»; без адреса — следующая по порядку."""
    letters = re.match(r"[A-Z]+", ref or "")
    if not letters:
        return default
    n = 0
    for ch in letters.group(0):
        n = n * 26 + ord(ch) - 64
    return n - 1


def _read(z, name):
    info = z.getinfo(name)
    if info.file_size > MAX_XML:
        raise RemainsError("Файл слишком большой.")
    return z.read(name)


def _text(node):
    return "".join(t.text or "" for t in node.iter(f"{{{NS['m']}}}t"))


def read_xlsx(data):
    """Первый лист книги как список строк (списков значений)."""
    try:
        z = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile:
        raise RemainsError("Это не файл Excel (.xlsx).")
    names = set(z.namelist())
    shared = []
    if "xl/sharedStrings.xml" in names:
        root = ET.fromstring(_read(z, "xl/sharedStrings.xml"))
        shared = [_text(si) for si in root.findall("m:si", NS)]

    sheet = "xl/worksheets/sheet1.xml"
    if "xl/workbook.xml" in names and "xl/_rels/workbook.xml.rels" in names:
        wb = ET.fromstring(_read(z, "xl/workbook.xml"))
        first = wb.find("m:sheets/m:sheet", NS)
        rels = ET.fromstring(_read(z, "xl/_rels/workbook.xml.rels"))
        if first is not None:
            rid = first.get(f"{{{NS['r']}}}id")
            for rel in rels.iter(f"{REL_NS}Relationship"):
                if rel.get("Id") == rid:
                    target = rel.get("Target").lstrip("/")
                    sheet = target if target.startswith("xl/") else "xl/" + target
    if sheet not in names:
        raise RemainsError("В файле Excel не найден лист с данными.")

    rows = []
    for row in ET.fromstring(_read(z, sheet)).iter(f"{{{NS['m']}}}row"):
        values = []
        for c in row.findall("m:c", NS):
            i = _col_index(c.get("r"), len(values))
            t = c.get("t")
            v = c.find("m:v", NS)
            if t == "s" and v is not None:
                val = shared[int(v.text)]
            elif t == "inlineStr":
                is_ = c.find("m:is", NS)
                val = _text(is_) if is_ is not None else ""
            elif v is not None:
                val = v.text
            else:
                val = ""
            while len(values) <= i:
                values.append("")
            values[i] = val
        rows.append(values)
    return rows


def read_csv(data):
    text = data.decode("utf-8-sig", errors="replace")
    dialect = csv.Sniffer().sniff(text[:4096], delimiters=";,\t") if text.strip() else csv.excel
    return [row for row in csv.reader(io.StringIO(text), dialect)]


# Заголовок в выгрузке → поле. Сравниваем без регистра и лишних пробелов.
COLUMNS = {
    "id лог объекта": "officeId",
    "имя лог объекта": "officeName",
    "id маршрута": "routeId",
    "автозаявки": "autoRequests",
    "время подачи тс": "arrivalTime",
    "парковка": "parking",
    "кол-во коробок": "boxes",
    "кол-во кгт": "kgt",
    "кол-во шк": "shk",
    "норма выезда шк": "shkNorm",
    "объём (л)": "volume",
    "объем (л)": "volume",
    "среднесуточное накопление, л": "dailyVolume",
    "перевозчики": "carriers",
    "дистанция в сторону, км": "distance",
}
INT_FIELDS = {"officeId", "routeId", "boxes", "kgt", "shk", "shkNorm"}
FLOAT_FIELDS = {"volume", "dailyVolume", "distance"}


def _norm(s):
    return " ".join(str(s or "").split()).casefold()


def _num(v, as_int):
    s = str(v or "").replace(" ", "").replace(" ", "").replace(",", ".")
    if not s:
        return None
    try:
        f = float(s)
    except ValueError:
        return None
    return int(round(f)) if as_int else round(f, 3)


def _carrier(s):
    """«10105118 ИП Иванов» → {"id": "10105118", "name": "ИП Иванов"}."""
    m = re.match(r"^\s*(\d+)\s+(.*)$", s or "")
    return {"id": m.group(1), "name": m.group(2).strip()} if m else {"id": None, "name": (s or "").strip()}


def parse_remains(data, filename=""):
    rows = read_csv(data) if filename.lower().endswith(".csv") else read_xlsx(data)
    header_at = next((i for i, r in enumerate(rows[:20])
                      if any(_norm(c) == "id маршрута" for c in r)), None)
    if header_at is None:
        raise RemainsError("Не нашёл колонку «ID маршрута». Загрузите выгрузку остатков из WB как есть.")
    header = [COLUMNS.get(_norm(c)) for c in rows[header_at]]
    if "shk" not in header:
        raise RemainsError("Не нашёл колонку «Кол-во ШК».")

    items = []
    for r in rows[header_at + 1:]:
        item = {}
        for field, value in zip(header, r):
            if not field:
                continue
            if field in INT_FIELDS:
                item[field] = _num(value, True)
            elif field in FLOAT_FIELDS:
                item[field] = _num(value, False)
            else:
                item[field] = str(value or "").strip()
        if item.get("routeId") is None:
            continue
        # В ячейке может быть несколько перевозчиков через запятую или с новой строки.
        raw = item.get("carriers") or ""
        item["carrierList"] = [_carrier(c) for c in re.split(r"[\n;]|,\s*(?=\d)", raw) if c.strip()]
        items.append(item)
    if not items:
        raise RemainsError("В файле нет строк с маршрутами.")
    return items


# ---------------------------------------------------------------- автозагрузка из WB

REMAINS_URL = "https://logistics.wb.ru/router-bff/last-mile-report/v1/report/by-routecar"
PAGE_SIZE = 50
MAX_PAGES = 40


def _int(v):
    try:
        return int(str(v))
    except (TypeError, ValueError):
        return None


def _scaled(v, divisor):
    n = _int(v)
    return None if n is None else round(n / divisor, 3)


def routecar_item(rc):
    """Строка ответа by-routecar → тот же вид, что и строка выгрузки Excel."""
    office = (rc.get("src") or {}).get("office") or {}
    auto = rc.get("auto_request") or {}
    parking = [str(p) for p in rc.get("parking_numbers") or []]
    return {
        "officeId": office.get("id"),
        "officeName": office.get("name") or "",
        "routeId": _int(rc.get("id")),
        "autoRequests": "да" if auto.get("enabled") else "нет",
        "arrivalTime": "",
        "parking": ", ".join(parking),
        "boxes": _int(rc.get("count_tares")),
        "kgt": _int(rc.get("count_kgt")),
        "shk": _int(rc.get("count_shk")),
        "shkNorm": _int(rc.get("normative_count_shk")),
        "normPercent": rc.get("to_normative_shk_percentage"),
        "volume": _scaled(rc.get("total_volume"), 1000),               # мл → л
        "dailyVolume": _scaled(rc.get("average_daily_volume"), 1000),  # мл → л
        "distance": _scaled(rc.get("direct_distance"), 1000),          # м → км
        "carrierList": [{"id": str(s.get("id")) if s.get("id") is not None else None,
                         "name": (s.get("name") or "").strip()} for s in rc.get("suppliers") or []],
    }


def fetch_remains(token, supplier_ids, post):
    """Все маршруты кабинета из отчёта «Остатки» (постранично)."""
    items = []
    for page in range(MAX_PAGES):
        data = post(REMAINS_URL, {
            "data": {"supplier_ids": [int(s) for s in supplier_ids], "src_office_ids": [], "geo_division_ids": []},
            "meta": {"page_size": PAGE_SIZE, "skip": page * PAGE_SIZE, "sorting": {"shk_count": "SORT_ORDER_DESC"}},
        }, token)
        rows = data.get("data") if isinstance(data, dict) else None
        if not isinstance(rows, list):
            raise WbError("WB_FORMAT", "Неожиданный формат ответа WB по остаткам — возможно, API изменился.")
        items += [routecar_item(r["routecar"]) for r in rows if isinstance(r, dict) and isinstance(r.get("routecar"), dict)]
        if not (data.get("meta") or {}).get("has_next"):
            break
    return [i for i in items if i["routeId"] is not None]
