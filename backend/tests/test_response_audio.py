import base64
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock

from app.infrastructure.ai.openai_provider import OpenAIButlerProvider


def completion(transcript: str, audio: bytes) -> SimpleNamespace:
    output = SimpleNamespace(transcript=transcript, data=base64.b64encode(audio).decode())
    return SimpleNamespace(
        choices=[SimpleNamespace(message=SimpleNamespace(audio=output))]
    )


class ResponseAudioTests(unittest.IsolatedAsyncioTestCase):
    def test_message_matching_allows_small_spoken_variations(self):
        self.assertTrue(
            OpenAIButlerProvider._same_message(
                "Your meeting is at three PM tomorrow.",
                "Your meeting is at 3 p.m. tomorrow!",
            )
        )
        self.assertFalse(
            OpenAIButlerProvider._same_message(
                "Your meeting was cancelled.",
                "Your meeting is at three PM tomorrow.",
            )
        )

    async def test_transcript_mismatch_warns_but_keeps_valid_wav(self):
        provider = OpenAIButlerProvider(api_key="", model="text-model")
        wav = b"RIFF" + (b"\x00" * 4) + b"WAVE" + (b"\x00" * 32)
        create = AsyncMock(return_value=completion("A different transcript", wav))
        provider._client = SimpleNamespace(
            chat=SimpleNamespace(completions=SimpleNamespace(create=create))
        )

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "response.wav"
            with self.assertLogs(
                "app.infrastructure.ai.openai_provider", "WARNING"
            ) as logs:
                mime_type = await provider.synthesize("Canonical response", path)
            self.assertEqual(path.read_bytes(), wav)

        self.assertEqual(mime_type, "audio/wav")
        self.assertIn("accepting generated audio", " ".join(logs.output))

    async def test_invalid_wav_is_not_written(self):
        provider = OpenAIButlerProvider(api_key="", model="text-model")
        create = AsyncMock(return_value=completion("Canonical response", b"not-a-wav"))
        provider._client = SimpleNamespace(
            chat=SimpleNamespace(completions=SimpleNamespace(create=create))
        )

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "response.wav"
            with self.assertRaisesRegex(RuntimeError, "invalid WAV"):
                await provider.synthesize("Canonical response", path)
            self.assertFalse(path.exists())


if __name__ == "__main__":
    unittest.main()
