"""Сборка ответа бота в HTML с разбиением на сообщения до 4096 символов."""

from __future__ import annotations

from html import escape

from .analyzer import Digest
from .collector import Post

TG_LIMIT = 4000  # с запасом от лимита Telegram в 4096


def _short(text: str, n: int) -> str:
    return text if len(text) <= n else text[: n - 1].rstrip() + "…"


def hours_label(hours: float) -> str:
    h = max(1, round(hours))
    if h < 48:
        return f"{h} ч"
    return f"{round(h / 24)} дн"


def format_digest(digest: Digest, posts: list[Post], header: str, footer: str = "") -> list[str]:
    by_id = {p.id: p for p in posts}
    blocks = [header]
    if digest.overview.strip():
        blocks.append(f"<i>{escape(digest.overview.strip())}</i>")

    for n, item in enumerate(digest.items, 1):
        links, seen_channels = [], set()
        for pid in item.post_ids:
            post = by_id.get(pid)
            if post is None or post.channel_title in seen_channels:
                continue
            seen_channels.add(post.channel_title)
            links.append(f'<a href="{escape(post.url)}">{escape(_short(post.channel_title, 32))}</a>')

        block = f"<b>{n}. {escape(_short(item.title, 200))}</b>\n{escape(_short(item.summary, 1500))}"
        if item.why.strip():
            block += f"\n<i>→ {escape(_short(item.why.strip(), 300))}</i>"
        if links:
            block += "\n🔗 " + " · ".join(links[:6])
        blocks.append(block)

    if not digest.items:
        blocks.append("Ничего по-настоящему интересного не нашлось 🤷")
    if footer:
        blocks.append(footer)
    return pack(blocks)


def pack(blocks: list[str], limit: int = TG_LIMIT) -> list[str]:
    """Склеивает блоки в сообщения, не разрывая блок посередине."""
    messages: list[str] = []
    current = ""
    for block in blocks:
        candidate = f"{current}\n\n{block}" if current else block
        if len(candidate) <= limit:
            current = candidate
            continue
        if current:
            messages.append(current)
        current = block
    if current:
        messages.append(current)
    return messages
