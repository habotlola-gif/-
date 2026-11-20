"""Настройки из .env."""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv

load_dotenv()


def _req(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        raise SystemExit(f"Не задана переменная {name} — заполните .env (см. .env.example)")
    return value


def _int(name: str, default: int) -> int:
    value = os.getenv(name, "").strip()
    return int(value) if value else default


@dataclass(frozen=True)
class Config:
    bot_token: str
    owner_id: int
    api_id: int
    api_hash: str
    model: str
    effort: str
    top_n: int
    default_hours: int
    max_lookback_hours: int
    max_posts_per_channel: int
    max_channels: int
    data_dir: Path

    @property
    def session_path(self) -> str:
        # Telethon сам добавит расширение .session
        return str(self.data_dir / "user")

    @property
    def state_path(self) -> Path:
        return self.data_dir / "state.json"


def load_telegram_user_config() -> tuple[int, str, Path]:
    """Только то, что нужно для входа в аккаунт (login.py)."""
    data_dir = Path(os.getenv("DATA_DIR", "data"))
    data_dir.mkdir(parents=True, exist_ok=True)
    return int(_req("TG_API_ID")), _req("TG_API_HASH"), data_dir


def load_config() -> Config:
    api_id, api_hash, data_dir = load_telegram_user_config()
    _req("ANTHROPIC_API_KEY")  # SDK прочитает ключ сам, здесь только проверяем наличие
    effort = os.getenv("DIGEST_EFFORT", "medium").strip() or "medium"
    if effort not in {"low", "medium", "high", "xhigh", "max"}:
        raise SystemExit("DIGEST_EFFORT должен быть одним из: low, medium, high, xhigh, max")
    return Config(
        bot_token=_req("BOT_TOKEN"),
        owner_id=int(_req("OWNER_ID")),
        api_id=api_id,
        api_hash=api_hash,
        model=os.getenv("DIGEST_MODEL", "claude-opus-5-5").strip() or "claude-opus-5-5",
        effort=effort,
        top_n=_int("TOP_N", 7),
        default_hours=_int("DEFAULT_HOURS", 24),
        max_lookback_hours=_int("MAX_LOOKBACK_HOURS", 72),
        max_posts_per_channel=_int("MAX_POSTS_PER_CHANNEL", 40),
        max_channels=_int("MAX_CHANNELS", 60),
        data_dir=data_dir,
    )
