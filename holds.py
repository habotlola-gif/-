"""Удержания WB (претензии к перевозчику) и баланс — сервис drive.wb.ru.

Каждое удержание — одна позиция (ШК) с суммой, типом и комментарием, в котором
WB пишет номер путевого листа:
    тип 2: «Путевой лист №31246587 (Сколы/Трещины)»
    тип 3: «Недоставленные коробки / Путевой лист: 31224066 / Дата отгрузки: 2026-09-13 / Возвратная коробка: Да»
"""
import re

from core import WbError, day_of, kopecks

PRETENSIONS_URL = "https://drive.wb.ru/client-gateway/api/v1/penalties/carrier/pretensions/page"
BALANCE_URL = "https://drive.wb.ru/client-gateway/api/finance/balance/v2/balances"
PAGE_SIZE = 100
MAX_PAGES = 200

TYPE_NAMES = {2: "Брак", 3: "Недоставленные коробки"}

_WAYSHEET_RE = re.compile(r"Путевой\s+лист\s*(?:№|:)?\s*(\d+)", re.I)
_REASON_RE = re.compile(r"Путевой\s+лист\s*№?\s*\d+\s*\((.+)\)\s*$", re.I)


def parse_comment(comment):
    """Номер путевого, вид (если WB пишет его текстом), причина и прочие поля из комментария."""
    text = (comment or "").strip()
    m = _WAYSHEET_RE.search(text)
    info = {"waysheetId": m.group(1) if m else None, "kind": None, "reason": None,
            "shipDate": None, "returnBox": None}
    r = _REASON_RE.search(text)
    if r:
        info["reason"] = r.group(1).strip()
    if " / " in text:
        parts = [p.strip() for p in text.split(" / ")]
        if not parts[0].lower().startswith("путевой"):
            info["kind"] = parts[0]
        for p in parts[1:]:
            key, _, value = p.partition(":")
            key, value = key.strip().lower(), value.strip()
            if key.startswith("дата отгрузки"):
                info["shipDate"] = value
            elif key.startswith("возвратная коробка"):
                info["returnBox"] = value
    return info


def parse_pretension(p):
    info = parse_comment(p.get("comment"))
    type_id = p.get("pret_type_id")
    type_name = TYPE_NAMES.get(type_id) or info["kind"] or f"Тип {type_id}"
    amount = p.get("amount")
    return {
        "id": str(p.get("pretension_id") or ""),
        "shkId": str(p.get("shk_id") or ""),
        "createdAt": p.get("create_dt"),
        "day": day_of(p.get("create_dt")),
        "amount": kopecks(amount.get("value") if isinstance(amount, dict) else amount),
        "typeId": type_id,
        "typeName": type_name,
        "reason": info["reason"] or (f"возвратная коробка: {info['returnBox'].lower()}" if info["returnBox"] else None),
        "comment": p.get("comment") or "",
        "waysheetId": info["waysheetId"],
        "shipDate": info["shipDate"],
        "statusId": p.get("status_id"),
        "refunded": bool(p.get("rop_refund_dt")),
        "refundDt": p.get("rop_refund_dt"),
        "officeName": p.get("office_name") or "",
        "deleted": bool(p.get("is_deleted")),
    }


def fetch_pretensions(token, supplier_id, date_from, date_to, post):
    """Все удержания поставщика за дни date_from..date_to включительно (по Москве)."""
    items, page_token = [], None
    for _ in range(MAX_PAGES):
        body = {"supplier_id": str(supplier_id), "date_from": f"{date_from}T00:00:00+03:00",
                "date_to": f"{date_to}T23:59:59+03:00", "page_size": PAGE_SIZE}
        if page_token:
            body["page_token"] = page_token
        data = post(PRETENSIONS_URL, body, token)
        rows = data.get("pretensions") if isinstance(data, dict) else None
        if not isinstance(rows, list):
            raise WbError("WB_FORMAT", "Неожиданный формат ответа WB по удержаниям — возможно, API изменился.")
        items += [parse_pretension(r) for r in rows if isinstance(r, dict)]
        page_token = data.get("next_page_token")
        if not page_token or not rows:
            break
    return [i for i in items if not i["deleted"]]


def fetch_balance(token, supplier_id, get):
    data = get(f"{BALANCE_URL}?supplier_id={int(supplier_id)}", token)
    d = data.get("data") if isinstance(data, dict) else None
    if not isinstance(d, dict):
        raise WbError("WB_FORMAT", "Неожиданный формат ответа WB по балансу — возможно, API изменился.")

    def val(k):
        v = d.get(k)
        return kopecks(v.get("value") if isinstance(v, dict) else v)

    return {"sum": val("sum"), "available": val("sum_available"), "fines": val("sum_fine"), "all": val("sum_all")}
