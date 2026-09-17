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
    tool_call = SimpleNamespace(
        type="function",
        function=SimpleNamespace(
            name="submit_butler_interaction",
            arguments=proposal.model_dump_json(),
        ),
    )
    output = SimpleNamespace(
        transcript=proposal.response_text,
        data=base64.b64encode(audio).decode(),
    )
    message = SimpleNamespace(tool_calls=[tool_call], audio=output)
    return SimpleNamespace(choices=[SimpleNamespace(message=message)])


def context() -> ButlerContext:
    return ButlerContext(
        conversation_history=[],
        user_context=[],
        daily_plan=None,
        relevant_events=[],
    )


class SingleCallInteractionTests(unittest.IsolatedAsyncioTestCase):
    def provider(self, result: SimpleNamespace) -> tuple[OpenAIButlerProvider, AsyncMock]:
        provider = OpenAIButlerProvider(api_key="", model="text-model")
        create = AsyncMock(return_value=result)
        provider._client = SimpleNamespace(
            chat=SimpleNamespace(completions=SimpleNamespace(create=create))
        )
        return provider, create

    async def test_audio_interaction_uses_one_call_with_original_audio_and_context(self):
        proposal = ButlerInteractionProposal(
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
            source = Path(directory) / "input.m4a"
            source.write_bytes(b"original-recording")
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
                    audio_mime_type="audio/mp4",
                    interaction_mode="order",
                    now="2026-09-17T18:00:00+07:00",
                    timezone="Asia/Ho_Chi_Minh",
                    context=context(),
                )

        create.assert_awaited_once()
        kwargs = create.await_args.kwargs
        self.assertEqual(kwargs["modalities"], ["text", "audio"])
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

    async def test_text_interaction_uses_the_same_single_call(self):
        proposal = ButlerInteractionProposal(
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
            user_message_text="Hello",
            decision={"intent": "query", "requested_action": "none"},
            response_text="Hello.",
        )
        result = completion(proposal, b"not-a-wav")
        result.choices[0].message.audio.data = "not-valid-base64"
        provider, create = self.provider(result)

        with self.assertLogs(
            "app.infrastructure.ai.openai_provider", "ERROR"
        ) as logs:
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
        self.assertIn("text-only fallback", " ".join(logs.output))

    async def test_mismatched_audio_is_not_returned_to_the_client(self):
        proposal = ButlerInteractionProposal(
            user_message_text="Move my meeting",
            decision={"intent": "query", "requested_action": "none"},
            response_text="Your meeting is at 3 PM.",
        )
        result = completion(proposal, b"model-wav")
        result.choices[0].message.audio.transcript = "Your meeting was cancelled."
        provider, create = self.provider(result)

        with self.assertLogs(
            "app.infrastructure.ai.openai_provider", "WARNING"
        ) as logs:
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
        self.assertIn("text-only fallback", " ".join(logs.output))

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


if __name__ == "__main__":
    unittest.main()
