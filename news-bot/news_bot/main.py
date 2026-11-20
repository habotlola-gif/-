"""Telegram-бот: кнопки, управление каналами и интересами, запуск дайджеста."""

from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timedelta, timezone
from html import escape

from aiogram import Bot, Dispatcher, F, Router
from aiogram.client.default import DefaultBotProperties
from aiogram.enums import ParseMode
from aiogram.filters import Command, CommandObject, CommandStart
from aiogram.fsm.context import FSMContext
from aiogram.fsm.state import State, StatesGroup
from aiogram.types import (
    BotCommand,
    CallbackQuery,
    InlineKeyboardButton,
    InlineKeyboardMarkup,
    KeyboardButton,
    Message,
    MessageOriginChannel,
    ReplyKeyboardMarkup,
)
from aiogram.utils.chat_action import ChatActionSender

from .analyzer import Analyzer, AnalyzerError, select_posts
from .collector import Collector, channel_ref, parse_channel_ref
from .config import Config, load_config
from .formatting import format_digest, hours_label
from .storage import Storage

log = logging.getLogger(__name__)

BTN_FIND = "🔥 Найти интересное"
BTN_CHANNELS = "📋 Каналы"
BTN_INTERESTS = "⚙️ Интересы"


class Form(StatesGroup):
    interests = State()


def extract_refs(text: str, *, strict: bool) -> tuple[list[str], list[str]]:
    """Возвращает (распознанные ссылки на каналы, нераспознанные куски).

    strict=True — считаем каналом только '@name', ссылки t.me и '-100…',
    чтобы обычные слова в чате случайно не превращались в каналы.
    """
    refs, bad = [], []
    for token in text.replace(",", " ").split():
        looks_like_ref = token.startswith("@") or "t.me/" in token or token.startswith("-100")
        if strict and not looks_like_ref:
            bad.append(token)
            continue
        ref = parse_channel_ref(token)
        (refs if ref else bad).append(ref or token)
    return refs, bad


class App:
    def __init__(self, cfg: Config, store: Storage, collector: Collector, analyzer: Analyzer):
        self.cfg = cfg
        self.store = store
        self.collector = collector
        self.analyzer = analyzer
        self.digest_lock = asyncio.Lock()
        self.btn_day = f"🕐 За {hours_label(cfg.default_hours)}"

    # ---------- клавиатуры и тексты ----------

    def keyboard(self) -> ReplyKeyboardMarkup:
        return ReplyKeyboardMarkup(
            keyboard=[
                [KeyboardButton(text=BTN_FIND)],
                [KeyboardButton(text=self.btn_day), KeyboardButton(text=BTN_CHANNELS), KeyboardButton(text=BTN_INTERESTS)],
            ],
            resize_keyboard=True,
            is_persistent=True,
        )

    def help_text(self) -> str:
        return (
            "Я читаю ваши Telegram-каналы и по кнопке нахожу самое интересное.\n\n"
            f"<b>{BTN_FIND}</b> — новое с прошлого нажатия\n"
            f"<b>{self.btn_day}</b> — всё за последние {hours_label(self.cfg.default_hours)}\n"
            f"<b>{BTN_CHANNELS}</b> — какие каналы читать\n"
            f"<b>{BTN_INTERESTS}</b> — что для вас интересно\n\n"
            "Чтобы добавить канал, перешлите мне любой пост из него или отправьте "
            "<code>@канал</code> / ссылку <code>t.me/…</code>. Пока список пуст, "
            "я читаю все каналы, на которые вы подписаны."
        )

    def channels_view(self) -> tuple[str, InlineKeyboardMarkup | None]:
        channels = self.store.state.channels
        if not channels:
            return (
                "📋 Список пуст — читаю <b>все каналы из ваших подписок</b> "
                f"(до {self.cfg.max_channels} самых свежих).\n\n"
                "Чтобы читать только выбранные, перешлите мне пост из канала "
                "или отправьте <code>@канал</code> / ссылку <code>t.me/…</code>.",
                None,
            )
        lines = "\n".join(f"• {escape(c)}" for c in channels)
        text = (
            f"📋 Читаю {len(channels)} канал(ов):\n{lines}\n\n"
            "Добавить — перешлите пост или отправьте <code>@канал</code>. "
            "Удалить — кнопкой ниже."
        )
        buttons = [[InlineKeyboardButton(text=f"❌ {c}", callback_data=f"rm:{c}")] for c in channels]
        return text, InlineKeyboardMarkup(inline_keyboard=buttons)

    # ---------- действия ----------

    async def add_channels(self, message: Message, refs: list[str], bad: list[str]) -> None:
        lines = []
        for ref in refs:
            try:
                entity = await self.collector.resolve(ref)
            except ValueError as e:
                lines.append(f"⚠️ {escape(str(e))}")
                continue
            normalized = channel_ref(entity)
            if self.store.add_channel(normalized):
                lines.append(f"✅ Добавил «{escape(entity.title)}» ({escape(normalized)})")
            else:
                lines.append(f"Уже в списке: «{escape(entity.title)}»")
        if bad:
            lines.append("Не понял: " + ", ".join(escape(b) for b in bad))
        await message.answer("\n".join(lines) or "Не нашёл каналов в сообщении")

    async def run_digest(self, message: Message, *, since_last: bool) -> None:
        if self.digest_lock.locked():
            await message.answer("Уже ищу, подождите немного ⏳")
            return
        async with self.digest_lock:
            await self._run_digest(message, since_last=since_last)

    async def _run_digest(self, message: Message, *, since_last: bool) -> None:
        cfg, state = self.cfg, self.store.state
        started = datetime.now(timezone.utc)
        if since_last and state.last_run_dt:
            since = max(state.last_run_dt, started - timedelta(hours=cfg.max_lookback_hours))
            period = f"с прошлого раза (за {hours_label((started - since).total_seconds() / 3600)})"
        else:
            since = started - timedelta(hours=cfg.default_hours)
            period = f"за {hours_label(cfg.default_hours)}"

        status = await message.answer("⏳ Читаю каналы…")
        try:
            collected = await self.collector.collect(state.channels, since)
        except Exception:
            log.exception("Ошибка чтения каналов")
            await status.edit_text("Не получилось прочитать каналы — подробности в логе бота")
            return

        failed_note = ""
        if collected.failed:
            failed_note = "⚠️ Не удалось прочитать: " + ", ".join(escape(f) for f in collected.failed)

        if not collected.posts:
            text = f"За период {period} новых постов нет."
            await status.edit_text(f"{text}\n\n{failed_note}" if failed_note else text)
            if since_last:
                self.store.set_last_run(started)
            return

        selection = select_posts(collected.posts, cfg.max_input_chars, cfg.max_post_chars)
        await status.edit_text(
            f"🧠 Выбираю интересное из {len(selection.posts)} постов "
            f"({collected.channels_read} каналов)… Бесплатной модели может понадобиться пара минут."
        )
        try:
            async with ChatActionSender.typing(bot=message.bot, chat_id=message.chat.id):
                analysis = await self.analyzer.analyze(selection.posts, state.interests)
        except AnalyzerError as e:
            await status.edit_text(f"😕 {escape(str(e))}")
            return
        except Exception:
            log.exception("Ошибка анализа")
            await status.edit_text("😕 Что-то пошло не так при анализе — подробности в логе бота")
            return

        seen = f"Просмотрено {len(selection.posts)} постов из {collected.channels_read} каналов"
        if selection.skipped:
            seen += f" (ещё {selection.skipped} менее популярных не влезли в лимит модели)"
        header = f"🔥 <b>Интересное {period}</b>\n<i>{seen}</i>"
        footer = "\n".join(filter(None, [failed_note, f"<i>Модель: {escape(analysis.model)}</i>"]))
        chunks = format_digest(analysis.digest, selection.posts, header, footer)
        await status.delete()
        for chunk in chunks:
            await message.answer(chunk, reply_markup=self.keyboard())
        if since_last:
            self.store.set_last_run(started)


def build_router(app: App) -> Router:
    router = Router(name="owner")
    router.message.filter(F.from_user.id == app.cfg.owner_id)
    router.callback_query.filter(F.from_user.id == app.cfg.owner_id)

    @router.message(CommandStart())
    @router.message(Command("help"))
    async def start(message: Message, state: FSMContext) -> None:
        await state.clear()
        await message.answer(app.help_text(), reply_markup=app.keyboard())

    @router.message(Command("cancel"))
    async def cancel(message: Message, state: FSMContext) -> None:
        await state.clear()
        await message.answer("Отменено", reply_markup=app.keyboard())

    @router.message(F.text == BTN_FIND)
    @router.message(Command("digest"))
    async def find(message: Message, state: FSMContext) -> None:
        await state.clear()
        await app.run_digest(message, since_last=True)

    @router.message(F.text == app.btn_day)
    @router.message(Command("day"))
    async def find_day(message: Message, state: FSMContext) -> None:
        await state.clear()
        await app.run_digest(message, since_last=False)

    @router.message(F.text == BTN_CHANNELS)
    @router.message(Command("channels"))
    async def channels(message: Message, state: FSMContext) -> None:
        await state.clear()
        text, markup = app.channels_view()
        await message.answer(text, reply_markup=markup)

    @router.callback_query(F.data.startswith("rm:"))
    async def remove_button(query: CallbackQuery) -> None:
        ref = query.data.removeprefix("rm:")
        removed = app.store.remove_channel(ref)
        await query.answer("Удалил" if removed else "Уже удалён")
        text, markup = app.channels_view()
        await query.message.edit_text(text, reply_markup=markup)

    @router.message(Command("add"))
    async def add_cmd(message: Message, command: CommandObject) -> None:
        refs, bad = extract_refs(command.args or "", strict=False)
        if not refs and not bad:
            await message.answer("Напишите так: <code>/add @канал</code>")
            return
        await app.add_channels(message, refs, bad)

    @router.message(Command("remove"))
    async def remove_cmd(message: Message, command: CommandObject) -> None:
        refs, _ = extract_refs(command.args or "", strict=False)
        if not refs:
            await message.answer("Напишите так: <code>/remove @канал</code>")
            return
        done = [r for r in refs if app.store.remove_channel(r)]
        await message.answer(("Удалил: " + ", ".join(done)) if done else "Таких каналов нет в списке")

    @router.message(F.text == BTN_INTERESTS)
    @router.message(Command("interests"))
    async def interests(message: Message, state: FSMContext, command: CommandObject | None = None) -> None:
        if command and command.args:
            app.store.set_interests(command.args.strip())
            await message.answer("✅ Интересы обновлены")
            return
        await state.set_state(Form.interests)
        await message.answer(
            "⚙️ Сейчас я ищу вот что:\n\n"
            f"<blockquote>{escape(app.store.state.interests)}</blockquote>\n\n"
            "Пришлите новое описание своими словами — что интересно и что нет. "
            "Например: «ИИ и стартапы, крипта только крупные события, без политики и рекламы».\n"
            "/cancel — оставить как есть."
        )

    @router.message(Form.interests, F.text)
    async def interests_input(message: Message, state: FSMContext) -> None:
        app.store.set_interests(message.text.strip())
        await state.clear()
        await message.answer("✅ Запомнил. Нажмите «Найти интересное», чтобы проверить.", reply_markup=app.keyboard())

    @router.message(F.forward_origin.as_("origin"))
    async def forwarded(message: Message, origin) -> None:
        if not isinstance(origin, MessageOriginChannel):
            await message.answer("Это не пост из канала. Перешлите сообщение именно из канала.")
            return
        chat = origin.chat
        ref = f"@{chat.username}" if chat.username else str(chat.id)
        await app.add_channels(message, [ref], [])

    @router.message(F.text)
    async def any_text(message: Message) -> None:
        refs, _ = extract_refs(message.text, strict=True)
        if refs:
            await app.add_channels(message, refs, [])
        else:
            await message.answer("Нажмите кнопку внизу 👇 или /help", reply_markup=app.keyboard())

    return router


def build_stranger_router() -> Router:
    router = Router(name="strangers")

    @router.message()
    async def stranger(message: Message) -> None:
        await message.answer("Это личный бот 🙂")

    return router


async def run() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    cfg = load_config()
    store = Storage(cfg.state_path)
    collector = Collector(cfg)
    await collector.start()
    app = App(cfg, store, collector, Analyzer(cfg))

    bot = Bot(
        cfg.bot_token,
        default=DefaultBotProperties(parse_mode=ParseMode.HTML, link_preview_is_disabled=True),
    )
    dp = Dispatcher()
    dp.include_router(build_router(app))
    dp.include_router(build_stranger_router())
    await bot.set_my_commands(
        [
            BotCommand(command="digest", description="Найти интересное с прошлого раза"),
            BotCommand(command="day", description=f"Интересное за {hours_label(cfg.default_hours)}"),
            BotCommand(command="channels", description="Список каналов"),
            BotCommand(command="add", description="Добавить канал: /add @канал"),
            BotCommand(command="interests", description="Что мне интересно"),
            BotCommand(command="help", description="Помощь"),
        ]
    )
    log.info("Бот запущен. Модель: %s (%s)", cfg.llm_model, cfg.llm_base_url)
    try:
        await dp.start_polling(bot)
    finally:
        await collector.stop()
        await bot.session.close()


def main() -> None:
    asyncio.run(run())
