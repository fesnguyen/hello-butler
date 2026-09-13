from pathlib import Path
from typing import Protocol


class ButlerResponseAudioProvider(Protocol):
    async def synthesize(self, text: str, path: Path) -> str: ...
