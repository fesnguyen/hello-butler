import uuid
from datetime import datetime

from sqlalchemy import Boolean, DateTime, ForeignKey, Index, String, Text
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.infrastructure.db.base import Base
from app.infrastructure.db.mixins import TimestampMixin


class ConversationMessageModel(TimestampMixin, Base):
    __tablename__ = "conversation_messages"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    role: Mapped[str] = mapped_column(String(40), nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    message_metadata: Mapped[dict[str, object]] = mapped_column(JSONB, default=dict, nullable=False)


class ButlerRequestModel(TimestampMixin, Base):
    __tablename__ = "butler_requests"
    __table_args__ = (Index("ix_butler_requests_recovery", "status", "updated_at"),)

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    input_source: Mapped[str] = mapped_column(String(20), nullable=False)
    interaction_mode: Mapped[str] = mapped_column(String(20), nullable=False)
    status: Mapped[str] = mapped_column(String(20), default="accepted", nullable=False)
    submitted_text: Mapped[str | None] = mapped_column(Text)
    input_audio_path: Mapped[str | None] = mapped_column(Text)
    input_audio_mime_type: Mapped[str | None] = mapped_column(String(120))
    user_message_text: Mapped[str | None] = mapped_column(Text)
    response_text: Mapped[str | None] = mapped_column(Text)
    response_audio_path: Mapped[str | None] = mapped_column(Text)
    response_audio_mime_type: Mapped[str | None] = mapped_column(String(120))
    response_audio_duration_ms: Mapped[int | None] = mapped_column()
    changed_entities: Mapped[list[dict[str, object]]] = mapped_column(
        JSONB, default=list, nullable=False
    )
    requires_follow_up: Mapped[bool] = mapped_column(default=False, nullable=False)
    reasoning_credit_charged: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    tts_credit_charged: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    failure_reason: Mapped[str | None] = mapped_column(Text)
    completed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    input_audio_delete_after: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    response_audio_delete_after: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class ButlerActionReceiptModel(TimestampMixin, Base):
    __tablename__ = "butler_action_receipts"

    request_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("butler_requests.id", ondelete="CASCADE"), primary_key=True
    )
    result: Mapped[dict[str, object]] = mapped_column(JSONB, nullable=False)
