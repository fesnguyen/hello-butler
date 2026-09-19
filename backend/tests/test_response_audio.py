import base64
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

from app.application.butler import (
    ButlerContext,
    ButlerInteractionProposal,
)
from app.infrastructure.ai.openai_provider import OpenAIButlerProvider


def completion(proposal: ButlerInteractionProposal, audio: bytes) -> SimpleNamespace:
    output = SimpleNamespace(
        transcript=proposal.response_text,
        data=base64.b64encode(audio).decode(),
    )
    message = SimpleNamespace(content=proposal.model_dump_json(), audio=output)
    return SimpleNamespace(choices=[SimpleNamespace(message=message)])


def response(proposal: ButlerInteractionProposal) -> SimpleNamespace:
    call = SimpleNamespace(
        type="function_call",
        name="submit_butler_interaction",
        arguments=proposal.model_dump_json(),
    )
    return SimpleNamespace(output=[call])


def context() -> ButlerContext:
    return ButlerContext(
        conversation_history=[],
        user_context=[],
        daily_plan=None,
        relevant_events=[],
    )


class SingleCallInteractionTests(unittest.IsolatedAsyncioTestCase):
    def provider(
        self,
        result: SimpleNamespace,
        *,
        interaction_api: str = "chat_completions",
    ) -> tuple[OpenAIButlerProvider, AsyncMock]:
        provider = OpenAIButlerProvider(
            api_key="",
            model="text-model",
            interaction_api=interaction_api,  # type: ignore[arg-type]
        )
        create = AsyncMock(return_value=result)
        provider._client = SimpleNamespace(
            chat=SimpleNamespace(completions=SimpleNamespace(create=create)),
            responses=SimpleNamespace(create=create),
        )
        return provider, create

    async def test_audio_interaction_uses_one_call_with_original_audio_and_context(self):
        proposal = ButlerInteractionProposal(
            thought="The user asked to move the meeting and the response confirms it.",
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
        provider, create = self.provider(completion(proposal, b"model-wav"))
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "input.ogg"
            source.write_bytes(b"OggS" + b"0" * 24 + b"OpusHead")
            with (
                patch.object(provider, "_wav_audio", AsyncMock(return_value=b"input-wav")),
                patch.object(
                    provider,
                    "_normalize_response_wav",
                    AsyncMock(return_value=b"RIFF0000WAVEnormalized"),
                ),
            ):
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
        self.assertEqual(kwargs["modalities"], ["text", "audio"])
        self.assertNotIn("tools", kwargs)
        self.assertNotIn("tool_choice", kwargs)
        user_content = kwargs["messages"][1]["content"]
        self.assertEqual(
            user_content[1]["input_audio"]["data"],
            base64.b64encode(b"input-wav").decode(),
        )
        supplied = json.loads(user_content[0]["text"])
        self.assertIn("context", supplied)
        self.assertEqual(supplied["now"], "2026-09-17T18:00:00+07:00")
        self.assertEqual(result.proposal, proposal)
        self.assertEqual(result.response_audio, b"RIFF0000WAVEnormalized")
        self.assertEqual(result.response_audio_mime_type, "audio/wav")

    async def test_responses_mode_uses_one_tool_call_and_falls_back_to_text_only(self):
        proposal = ButlerInteractionProposal(
            thought="The user asked a plan query and the response answers it.",
            user_message_text="What is on my plan?",
            decision={"intent": "query", "requested_action": "none"},
            response_text="You have no events today.",
        )
        provider, create = self.provider(response(proposal), interaction_api="responses")

        with self.assertLogs("app.infrastructure.ai.openai_provider", "WARNING") as logs:
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
        kwargs = create.await_args.kwargs
        self.assertEqual(kwargs["tool_choice"]["name"], "submit_butler_interaction")
        self.assertEqual(kwargs["input"][0]["content"][0]["type"], "input_text")
        self.assertEqual(result.proposal, proposal)
        self.assertEqual(result.response_audio, b"")
        self.assertIn("api=responses cause=openai_no_audio", " ".join(logs.output))

    async def test_text_interaction_uses_the_same_single_call(self):
        proposal = ButlerInteractionProposal(
            thought="The user asked a plan query and the response answers it.",
            user_message_text="What is on my plan?",
            decision={"intent": "query", "requested_action": "none"},
            response_text="You have no events today.",
        )
        provider, create = self.provider(completion(proposal, b"model-wav"))
        with (
            patch.object(provider, "_wav_audio", AsyncMock()) as decode,
            patch.object(
                provider,
                "_normalize_response_wav",
                AsyncMock(return_value=b"RIFF0000WAVEtext"),
            ),
        ):
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
        self.assertEqual(result.proposal.response_text, "You have no events today.")

    async def test_malformed_returned_audio_falls_back_to_text_without_another_call(self):
        proposal = ButlerInteractionProposal(
            thought="The response greets the user.",
            user_message_text="Hello",
            decision={"intent": "query", "requested_action": "none"},
            response_text="Hello.",
        )
        result = completion(proposal, b"not-a-wav")
        result.choices[0].message.audio.data = "not-valid-base64"
        provider, create = self.provider(result)

        with self.assertLogs("app.infrastructure.ai.openai_provider", "WARNING") as logs:
            interaction = await provider.interact(
                message="Hello",
                audio_path=None,
                audio_mime_type=None,
                interaction_mode="talk",
                now="2026-09-17T18:00:00+07:00",
                timezone="Asia/Ho_Chi_Minh",
                context=context(),
            )

        create.assert_awaited_once()
        self.assertEqual(interaction.response_audio, b"")
        self.assertIn("base64_decode_failed", " ".join(logs.output))

    async def test_mismatched_audio_is_not_returned_to_the_client(self):
        proposal = ButlerInteractionProposal(
            thought="The response confirms the requested meeting move.",
            user_message_text="Move my meeting",
            decision={"intent": "query", "requested_action": "none"},
            response_text=(
                "The quarterly planning appointment has been moved into tomorrow's "
                "afternoon calendar slot with the design leadership group."
            ),
        )
        result = completion(proposal, b"model-wav")
        result.choices[0].message.audio.transcript = (
            "Severe tropical rainfall will reach remote coastal villages overnight "
            "while emergency crews prepare evacuation shelters nearby."
        )
        provider, create = self.provider(result)

        with (
            self.assertLogs("app.infrastructure.ai.openai_provider", "WARNING") as logs,
            patch.object(
                provider,
                "_normalize_response_wav",
                AsyncMock(return_value=b"RIFF0000WAVEvalid"),
            ),
        ):
            interaction = await provider.interact(
                message="Move my meeting",
                audio_path=None,
                audio_mime_type=None,
                interaction_mode="talk",
                now="2026-09-17T18:00:00+07:00",
                timezone="Asia/Ho_Chi_Minh",
                context=context(),
            )

        create.assert_awaited_once()
        self.assertEqual(interaction.response_audio, b"")
        self.assertIn("materially_unrelated", " ".join(logs.output))

    def test_message_matching_allows_small_spoken_variations(self):
        self.assertTrue(
            OpenAIButlerProvider._same_message(
                "Your meeting is at three PM tomorrow.",
                "Your meeting is at 3 p.m. tomorrow!",
            )
        )
        self.assertFalse(
            OpenAIButlerProvider._same_message(
                "Severe tropical rainfall will reach remote coastal villages overnight "
                "while emergency crews prepare evacuation shelters nearby.",
                "The quarterly planning appointment has been moved into tomorrow's "
                "afternoon calendar slot with the design leadership group.",
            )
        )

    def test_message_matching_preserves_moderate_paraphrase(self):
        self.assertTrue(
            OpenAIButlerProvider._same_message(
                "Okay, I've shifted tomorrow's appointment to the afternoon.",
                "Got it. Your meeting is now scheduled for 3:00 PM tomorrow.",
            )
        )

    def test_message_matching_preserves_short_semantic_paraphrase_without_overlap(self):
        spoken = "Sure, I've moved it."
        canonical = "Done. Your meeting is now at 3 PM."
        spoken_words = OpenAIButlerProvider._meaningful_words(
            OpenAIButlerProvider._message_words(spoken)
        )
        canonical_words = OpenAIButlerProvider._meaningful_words(
            OpenAIButlerProvider._message_words(canonical)
        )
        self.assertFalse(spoken_words & canonical_words)
        self.assertTrue(OpenAIButlerProvider._same_message(spoken, canonical))

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


if __name__ == "__main__":
    unittest.main()
