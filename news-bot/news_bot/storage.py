"""Список каналов, интересы и время последнего дайджеста — в одном JSON-файле."""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from datetime import datetime
from pathlib import Path

DEFAULT_INTERESTS = (
    "Важные и неожиданные новости: технологии и ИИ, наука, экономика и деньги, "
    "крупные события в мире и в России. Конкретика, цифры, первоисточники. "
    "Не интересно: реклама, розыгрыши, гороскопы, мотивационные посты, "
    "самопиар каналов, пересказ одного и того же без новых фактов."
)


@dataclass
class State:
    # "@username" или числовой id вида "-100123..." для приватных каналов
    channels: list[str] = field(default_factory=list)
    interests: str = DEFAULT_INTERESTS
    last_run: str | None = None  # ISO-время последнего дайджеста «с прошлого раза»

    @property
    def last_run_dt(self) -> datetime | None:
        return datetime.fromisoformat(self.last_run) if self.last_run else None


class Storage:
    def __init__(self, path: Path):
        self.path = path
        self.state = self._load()

    def _load(self) -> State:
        if not self.path.exists():
            return State()
        raw = json.loads(self.path.read_text(encoding="utf-8"))
        known = {k: v for k, v in raw.items() if k in State.__dataclass_fields__}
        return State(**known)

    def save(self) -> None:
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(asdict(self.state), ensure_ascii=False, indent=2), encoding="utf-8")
        tmp.replace(self.path)

    def add_channel(self, ref: str) -> bool:
        if ref.lower() in (c.lower() for c in self.state.channels):
            return False
        self.state.channels.append(ref)
        self.save()
        return True

    def remove_channel(self, ref: str) -> bool:
        for c in self.state.channels:
            if c.lower() == ref.lower():
                self.state.channels.remove(c)
                self.save()
                return True
        return False

    def set_interests(self, text: str) -> None:
        self.state.interests = text
        self.save()

    def set_last_run(self, when: datetime) -> None:
        self.state.last_run = when.isoformat()
        self.save()
