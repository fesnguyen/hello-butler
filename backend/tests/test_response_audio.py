import base64
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

from app.application.butler import ButlerContext, ButlerInteractionProposal
from app.infrastructure.ai.openai_provider import (
    OpenAIButlerProvider,
    OpenAIButlerVoiceProvider,
)


def completion(proposal: ButlerInteractionProposal) -> SimpleNamespace:
    function = SimpleNamespace(
        name="submit_butler_interaction", arguments=proposal.model_dump_json()
    )
    call = SimpleNamespace(type="function", function=function)
    message = SimpleNamespace(content=None, audio=None, tool_calls=[call])
    return SimpleNamespace(choices=[SimpleNamespace(message=message)])


def context() -> ButlerContext:
    return ButlerContext(
        conversation_history=[], user_context=[], daily_plan=None, relevant_events=[]
    )


class InteractionProviderTests(unittest.IsolatedAsyncioTestCase):
    def provider(self, result: SimpleNamespace) -> tuple[OpenAIButlerProvider, AsyncMock]:
        provider = OpenAIButlerProvider(api_key="", model="text-model")
        create = AsyncMock(return_value=result)
        provider._client = SimpleNamespace(
            chat=SimpleNamespace(completions=SimpleNamespace(create=create))
        )
        return provider, create

    async def test_audio_interaction_returns_structured_proposal_without_output_audio(self):
        proposal = ButlerInteractionProposal(
            thought="The user asked to move the meeting.",
            user_message_text="Move my meeting to 3 PM tomorrow.",
            decision={
                "intent": "command",
                "requested_action": "update_daily_event",
                "target_event_title": "meeting",
                "event_date": "2026-09-18",
                "start_time": "15:00",
            },
            response_text="Got it. I moved your meeting to 3:00 PM tomorrow.",
        )
        provider, create = self.provider(completion(proposal))
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "input.ogg"
            source.write_bytes(b"OggS" + b"0" * 24 + b"OpusHead")
            with patch.object(provider, "_wav_audio", AsyncMock(return_value=b"input-wav")):
                result = await provider.interact(
                    message=None,
                    audio_path=source,
                    audio_mime_type="audio/ogg",
                    interaction_mode="order",
                    now="2026-09-17T18:00:00+07:00",
                    timezone="Asia/Ho_Chi_Minh",
                    context=context(),
                )

        create.assert_awaited_once()
        kwargs = create.await_args.kwargs
        self.assertNotIn("modalities", kwargs)
        self.assertNotIn("audio", kwargs)
        self.assertEqual(
            kwargs["tool_choice"]["function"]["name"], "submit_butler_interaction"
        )
        user_content = kwargs["messages"][1]["content"]
        self.assertEqual(
            user_content[1]["input_audio"]["data"],
            base64.b64encode(b"input-wav").decode(),
        )
        supplied = json.loads(user_content[0]["text"])
        self.assertIn("context", supplied)
        self.assertEqual(result, proposal)

    async def test_text_interaction_returns_structured_proposal(self):
        proposal = ButlerInteractionProposal(
            thought="The user asked a plan query.",
            user_message_text="What is on my plan?",
            decision={"intent": "query", "requested_action": "none"},
            response_text="You have no events today.",
        )
        provider, create = self.provider(completion(proposal))
        with patch.object(provider, "_wav_audio", AsyncMock()) as decode:
            result = await provider.interact(
                message="What is on my plan?",
                audio_path=None,
                audio_mime_type=None,
                interaction_mode="talk",
                now="2026-09-17T18:00:00+07:00",
                timezone="Asia/Ho_Chi_Minh",
                context=context(),
            )
        create.assert_awaited_once()
        decode.assert_not_awaited()
        self.assertEqual(result.response_text, "You have no events today.")

    async def test_invalid_ogg_input_fails_before_openai(self):
        provider, create = self.provider(SimpleNamespace())
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "input.ogg"
            source.write_bytes(b"not-ogg")
            with self.assertRaisesRegex(Exception, "valid Ogg"):
                await provider._wav_audio(source, "audio/ogg")
        create.assert_not_awaited()

    async def test_ffmpeg_unavailable_is_distinct_input_failure(self):
        provider, _ = self.provider(SimpleNamespace())
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "input.ogg"
            source.write_bytes(b"OggS" + b"0" * 24 + b"OpusHead")
            with (
                patch("asyncio.create_subprocess_exec", side_effect=FileNotFoundError),
                self.assertRaisesRegex(Exception, "FFmpeg is unavailable"),
            ):
                await provider._wav_audio(source, "audio/ogg")


class VoiceProviderTests(unittest.IsolatedAsyncioTestCase):
    async def test_tts_receives_exact_canonical_text_and_returns_wav(self):
        provider = OpenAIButlerVoiceProvider(api_key="", model="tts-model", voice="alloy")
        create = AsyncMock(
            return_value=SimpleNamespace(content=b"RIFF0000WAVEgenerated-audio")
        )
        provider._client = SimpleNamespace(
            audio=SimpleNamespace(speech=SimpleNamespace(create=create))
        )
        canonical = "Done. Your meeting is now at 3 PM."

        speech = await provider.synthesize(text=canonical)

        create.assert_awaited_once_with(
            model="tts-model",
            voice="alloy",
            input=canonical,
            response_format="wav",
        )
        self.assertEqual(speech.audio, b"RIFF0000WAVEgenerated-audio")
        self.assertEqual(speech.mime_type, "audio/wav")

    async def test_invalid_tts_audio_fails_at_voice_boundary(self):
        provider = OpenAIButlerVoiceProvider(api_key="")
        provider._client = SimpleNamespace(
            audio=SimpleNamespace(
                speech=SimpleNamespace(
                    create=AsyncMock(return_value=SimpleNamespace(content=b"not-wav"))
                )
            )
        )
        with self.assertRaisesRegex(Exception, "invalid WAV"):
            await provider.synthesize(text="Hello")


if __name__ == "__main__":
    unittest.main()
