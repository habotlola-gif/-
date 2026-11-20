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


def _str(name: str, default: str) -> str:
    return os.getenv(name, "").strip() or default


@dataclass(frozen=True)
class Config:
    bot_token: str
    owner_id: int
    api_id: int
    api_hash: str
    llm_base_url: str
    llm_api_key: str
    llm_model: str
    llm_max_tokens: int
    top_n: int
    default_hours: int
    max_lookback_hours: int
    max_posts_per_channel: int
    max_channels: int
    max_input_chars: int
    max_post_chars: int
    data_dir: Path

    @property
    def session_path(self) -> str:
        # Telethon сам добавит расширение .session
        return str(self.data_dir / "user")

    @property
    def state_path(self) -> Path:
        return self.data_dir / "state.json"

    @property
    def is_local_llm(self) -> bool:
        return any(h in self.llm_base_url for h in ("localhost", "127.0.0.1", "0.0.0.0"))


def load_telegram_user_config() -> tuple[int, str, Path]:
    """Только то, что нужно для входа в аккаунт (login.py)."""
    data_dir = Path(os.getenv("DATA_DIR", "data"))
    data_dir.mkdir(parents=True, exist_ok=True)
    return int(_req("TG_API_ID")), _req("TG_API_HASH"), data_dir


def load_config() -> Config:
    api_id, api_hash, data_dir = load_telegram_user_config()
    cfg = Config(
        bot_token=_req("BOT_TOKEN"),
        owner_id=int(_req("OWNER_ID")),
        api_id=api_id,
        api_hash=api_hash,
        llm_base_url=_str("LLM_BASE_URL", "https://openrouter.ai/api/v1"),
        llm_api_key=os.getenv("LLM_API_KEY", "").strip(),
        llm_model=_str("LLM_MODEL", "openrouter/free"),
        llm_max_tokens=_int("LLM_MAX_TOKENS", 6000),
        top_n=_int("TOP_N", 7),
        default_hours=_int("DEFAULT_HOURS", 24),
        max_lookback_hours=_int("MAX_LOOKBACK_HOURS", 72),
        max_posts_per_channel=_int("MAX_POSTS_PER_CHANNEL", 40),
        max_channels=_int("MAX_CHANNELS", 60),
        max_input_chars=_int("MAX_INPUT_CHARS", 60000),
        max_post_chars=_int("MAX_POST_CHARS", 1000),
        data_dir=data_dir,
    )
    if not cfg.llm_api_key and not cfg.is_local_llm:
        raise SystemExit("Не задан LLM_API_KEY — получите бесплатный ключ (см. README) и впишите в .env")
    return cfg
