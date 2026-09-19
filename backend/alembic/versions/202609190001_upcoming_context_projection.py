"""Add structured actionable fields to User Context.

Revision ID: 202609190001
Revises: 202609120001
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "202609190001"
down_revision: str | None = "202609120001"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "user_context_entries",
        sa.Column("is_actionable", sa.Boolean(), server_default=sa.false(), nullable=False),
    )
    op.add_column("user_context_entries", sa.Column("title", sa.String(length=200)))
    op.add_column("user_context_entries", sa.Column("start_time", sa.Time()))
    op.add_column("user_context_entries", sa.Column("end_time", sa.Time()))
    op.add_column("user_context_entries", sa.Column("recurrence", sa.String(length=20)))
    op.add_column(
        "user_context_entries",
        sa.Column(
            "recurrence_days",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
    )
    op.add_column(
        "user_context_entries",
        sa.Column(
            "occurrence_exceptions",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
    )
    op.add_column(
        "user_context_entries",
        sa.Column("version", sa.Integer(), server_default="1", nullable=False),
    )


def downgrade() -> None:
    for column in (
        "version",
        "occurrence_exceptions",
        "recurrence_days",
        "recurrence",
        "end_time",
        "start_time",
        "title",
        "is_actionable",
    ):
        op.drop_column("user_context_entries", column)
