import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch

from app.application.butler import ButlerAIUnavailableError
from app.infrastructure.ai.openai_provider import OpenAIButlerProvider


class AudioLoggingTests(unittest.IsolatedAsyncioTestCase):
    async def test_decode_logs_sizes_ratio_and_elapsed_time(self):
        provider = OpenAIButlerProvider(api_key="", model="text-model")
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "input.m4a"
            source.write_bytes(b"encoded")
            process = AsyncMock()
            process.returncode = 0
            process.communicate.return_value = (b"decoded-audio", b"")
            with (
                patch("asyncio.create_subprocess_exec", return_value=process),
                self.assertLogs("app.infrastructure.ai.openai_provider", "INFO") as logs,
            ):
                decoded = await provider._wav_audio(source)

        self.assertEqual(decoded, b"decoded-audio")
        output = " ".join(logs.output)
        self.assertIn("Audio decode started request_id=None input_bytes=7", output)
        self.assertIn(
            "Audio decode completed request_id=None input_bytes=7 output_bytes=13", output
        )
        self.assertIn("expansion_ratio=", output)
        self.assertIn("elapsed_ms=", output)

    async def test_decode_failure_logs_only_operational_metadata(self):
        provider = OpenAIButlerProvider(api_key="", model="text-model")
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "private-name.m4a"
            source.write_bytes(b"private audio bytes")
            process = AsyncMock()
            process.returncode = 1
            process.communicate.return_value = (b"", b"private ffmpeg detail")
            with (
                patch("asyncio.create_subprocess_exec", return_value=process),
                self.assertLogs("app.infrastructure.ai.openai_provider", "WARNING") as logs,
                self.assertRaises(ButlerAIUnavailableError),
            ):
                await provider._wav_audio(source)

        output = " ".join(logs.output)
        self.assertIn("Audio decode failed request_id=None input_bytes=19", output)
        self.assertNotIn("private-name", output)
        self.assertNotIn("private ffmpeg detail", output)


if __name__ == "__main__":
    unittest.main()
