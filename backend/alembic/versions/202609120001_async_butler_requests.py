"""async Butler requests and canonical results

Revision ID: 202609120001
Revises: 202609070001
Create Date: 2026-09-12
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "202609120001"
down_revision: str | None = "202609070001"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "butler_requests",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("input_source", sa.String(length=20), nullable=False),
        sa.Column("interaction_mode", sa.String(length=20), nullable=False),
        sa.Column("status", sa.String(length=20), nullable=False),
        sa.Column("submitted_text", sa.Text(), nullable=True),
        sa.Column("input_audio_path", sa.Text(), nullable=True),
        sa.Column("input_audio_mime_type", sa.String(length=120), nullable=True),
        sa.Column("user_message_text", sa.Text(), nullable=True),
        sa.Column("response_text", sa.Text(), nullable=True),
        sa.Column("response_audio_path", sa.Text(), nullable=True),
        sa.Column("response_audio_mime_type", sa.String(length=120), nullable=True),
        sa.Column("response_audio_duration_ms", sa.Integer(), nullable=True),
        sa.Column("changed_entities", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("requires_follow_up", sa.Boolean(), nullable=False),
        sa.Column("failure_reason", sa.Text(), nullable=True),
        sa.Column("completed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("input_audio_delete_after", sa.DateTime(timezone=True), nullable=True),
        sa.Column("response_audio_delete_after", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(op.f("ix_butler_requests_user_id"), "butler_requests", ["user_id"])
    op.create_index("ix_butler_requests_recovery", "butler_requests", ["status", "updated_at"])
    op.create_table(
        "butler_action_receipts",
        sa.Column("request_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("result", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(["request_id"], ["butler_requests.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("request_id"),
    )


def downgrade() -> None:
    op.drop_table("butler_action_receipts")
    op.drop_index("ix_butler_requests_recovery", table_name="butler_requests")
    op.drop_index(op.f("ix_butler_requests_user_id"), table_name="butler_requests")
    op.drop_table("butler_requests")
