"""Чтение постов из каналов через ваш аккаунт Telegram (Telethon).

Обычный бот не может читать чужие каналы, поэтому каналы читаются от имени
вашего аккаунта: это видит всё, на что вы подписаны, включая приватные каналы,
а публичные каналы можно добавить даже без подписки.
"""

from __future__ import annotations

import asyncio
import logging
import re
from dataclasses import dataclass, field
from datetime import datetime

from telethon import TelegramClient, utils
from telethon.tl.types import Channel

from .config import Config

log = logging.getLogger(__name__)

_PRIVATE_LINK = re.compile(r"(?:https?://)?(?:t|telegram)\.me/c/(?P<id>\d+)", re.I)
_PUBLIC_LINK = re.compile(r"(?:https?://)?(?:t|telegram)\.me/(?:s/)?(?P<name>[A-Za-z0-9_]+)", re.I)
_USERNAME = re.compile(r"^@?(?P<name>[A-Za-z][A-Za-z0-9_]{3,31})$")
_NOT_CHANNEL_PATHS = {"c", "s", "joinchat", "addlist", "share", "proxy", "socks", "iv"}


def parse_channel_ref(text: str) -> str | None:
    """'@name', 'name', 't.me/name', 't.me/name/123', 't.me/c/123/45', '-100123' → '@name' или '-100123'."""
    text = text.strip()
    if re.fullmatch(r"-100\d{5,}", text):
        return text
    if m := _PRIVATE_LINK.search(text):
        return f"-100{m['id']}"
    if m := _PUBLIC_LINK.search(text):
        name = m["name"]
        if name.lower() in _NOT_CHANNEL_PATHS or not _USERNAME.match(name):
            return None
        return f"@{name}"
    if m := _USERNAME.match(text):
        return f"@{m['name']}"
    return None


def channel_username(entity: Channel) -> str | None:
    if entity.username:
        return entity.username
    for u in getattr(entity, "usernames", None) or []:
        if u.active:
            return u.username
    return None


def channel_ref(entity: Channel) -> str:
    name = channel_username(entity)
    return f"@{name}" if name else str(utils.get_peer_id(entity))


def post_url(entity: Channel, msg_id: int) -> str:
    name = channel_username(entity)
    if name:
        return f"https://t.me/{name}/{msg_id}"
    return f"https://t.me/c/{entity.id}/{msg_id}"


@dataclass
class Post:
    id: int  # порядковый номер в выборке, по нему модель ссылается на пост
    channel_title: str
    url: str
    date: datetime
    text: str
    views: int | None = None


@dataclass
class Collected:
    posts: list[Post] = field(default_factory=list)
    channels_read: int = 0
    failed: list[str] = field(default_factory=list)


class Collector:
    def __init__(self, cfg: Config):
        self.cfg = cfg
        self.client = TelegramClient(cfg.session_path, cfg.api_id, cfg.api_hash)

    async def start(self) -> None:
        await self.client.connect()
        if not await self.client.is_user_authorized():
            await self.client.disconnect()
            raise SystemExit("Аккаунт Telegram не подключён. Сначала выполните: python login.py")
        # Прогреваем кэш: без него Telethon не найдёт приватные каналы по числовому id
        await self.client.get_dialogs()

    async def stop(self) -> None:
        await self.client.disconnect()

    async def resolve(self, ref: str) -> Channel:
        """Находит канал по '@name' или '-100…'. Бросает ValueError, если это не канал."""
        try:
            entity = await self.client.get_entity(int(ref) if ref.startswith("-100") else ref)
        except (ValueError, TypeError) as e:
            raise ValueError(f"не нашёл {ref}") from e
        if not isinstance(entity, Channel):
            raise ValueError(f"{ref} — это не канал")
        return entity

    async def subscribed_channels(self) -> list[Channel]:
        channels: list[Channel] = []
        async for dialog in self.client.iter_dialogs():
            entity = dialog.entity
            if isinstance(entity, Channel) and entity.broadcast:
                channels.append(entity)
                if len(channels) >= self.cfg.max_channels:
                    break
        return channels

    async def collect(self, refs: list[str], since: datetime) -> Collected:
        """Посты новее `since` (UTC). Пустой `refs` — все каналы из подписок."""
        result = Collected()
        if refs:
            entities: list[Channel] = []
            for ref in refs:
                try:
                    entities.append(await self.resolve(ref))
                except ValueError:
                    result.failed.append(ref)
        else:
            entities = await self.subscribed_channels()

        sem = asyncio.Semaphore(4)  # не дёргаем Telegram слишком часто

        async def read(entity: Channel) -> list[Post] | None:
            async with sem:
                try:
                    out = []
                    async for msg in self.client.iter_messages(entity, limit=self.cfg.max_posts_per_channel):
                        if msg.date < since:
                            break
                        text = (msg.message or "").strip()
                        if not text:  # фото без подписи, служебные сообщения
                            continue
                        out.append(
                            Post(
                                id=0,
                                channel_title=entity.title,
                                url=post_url(entity, msg.id),
                                date=msg.date,
                                text=text,
                                views=msg.views,
                            )
                        )
                    return out
                except Exception:
                    log.exception("Не удалось прочитать %s", channel_ref(entity))
                    return None

        batches = await asyncio.gather(*(read(e) for e in entities))
        for entity, batch in zip(entities, batches):
            if batch is None:
                result.failed.append(channel_ref(entity))
            else:
                result.channels_read += 1
                result.posts.extend(batch)

        result.posts.sort(key=lambda p: p.date)
        for i, post in enumerate(result.posts, 1):
            post.id = i
        return result
