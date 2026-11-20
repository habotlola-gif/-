"""Одноразовый вход в ваш аккаунт Telegram, чтобы бот мог читать каналы.

Запуск: python login.py — спросит номер телефона, код из Telegram и, если есть,
пароль двухэтапной проверки. Сессия сохранится в data/user.session.
"""

import asyncio

from telethon import TelegramClient

from news_bot.config import load_telegram_user_config


async def main() -> None:
    api_id, api_hash, data_dir = load_telegram_user_config()
    client = TelegramClient(str(data_dir / "user"), api_id, api_hash)
    await client.start()
    me = await client.get_me()
    print(f"\nГотово! Вошли как {me.first_name} (id {me.id}).")
    print(f"Сессия сохранена в {data_dir / 'user.session'} — никому не передавайте этот файл.")
    print("Теперь запустите бота: python -m news_bot")
    await client.disconnect()


if __name__ == "__main__":
    asyncio.run(main())
