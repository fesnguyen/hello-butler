"""Track speech readiness independently of text for requests and proactive events.

Revision ID: 202609240001
Revises: 202609220001
"""

import sqlalchemy as sa
from alembic import op

revision = "202609240001"
down_revision = "202609220001"
branch_labels = None
depends_on = None


def upgrade():
    op.add_column(
        "butler_requests",
        sa.Column("audio_status", sa.String(20), server_default="unavailable", nullable=False),
    )
    op.add_column(
        "daily_events",
        sa.Column("audio_status", sa.String(20), server_default="unavailable", nullable=False),
    )
    op.add_column("daily_events", sa.Column("response_audio_path", sa.Text()))
    op.add_column("daily_events", sa.Column("response_audio_mime_type", sa.String(120)))
    op.add_column(
        "daily_events", sa.Column("response_audio_delete_after", sa.DateTime(timezone=True))
    )
    op.add_column(
        "daily_events",
        sa.Column("tts_credit_charged", sa.Boolean(), server_default=sa.false(), nullable=False),
    )
    op.execute(
        "UPDATE butler_requests SET audio_status = 'ready' WHERE response_audio_path IS NOT NULL"
    )
    op.execute(
        "UPDATE daily_events SET audio_status = 'pending' "
        "WHERE event_type IN ('morning_brief', 'good_night_summary') "
        "AND content IS NOT NULL AND speak_aloud = true AND deleted_at IS NULL"
    )


def downgrade():
    for column in (
        "tts_credit_charged",
        "response_audio_delete_after",
        "response_audio_mime_type",
        "response_audio_path",
        "audio_status",
    ):
        op.drop_column("daily_events", column)
    op.drop_column("butler_requests", "audio_status")
