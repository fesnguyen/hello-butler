"""Add User Settings credits and TTS preference.

Revision ID: 202609220001
Revises: 202609190001
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "202609220001"
down_revision: str | None = "202609190001"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "users",
        sa.Column("credits", sa.Integer(), server_default="100", nullable=False),
    )
    op.add_column(
        "users",
        sa.Column(
            "tts_method",
            sa.String(length=20),
            server_default="OPEN_SOURCE",
            nullable=False,
        ),
    )
    op.create_check_constraint("ck_users_credits_non_negative", "users", "credits >= 0")
    op.add_column(
        "butler_requests",
        sa.Column(
            "reasoning_credit_charged",
            sa.Boolean(),
            server_default=sa.false(),
            nullable=False,
        ),
    )
    op.add_column(
        "butler_requests",
        sa.Column(
            "tts_credit_charged",
            sa.Boolean(),
            server_default=sa.false(),
            nullable=False,
        ),
    )


def downgrade() -> None:
    op.drop_column("butler_requests", "tts_credit_charged")
    op.drop_column("butler_requests", "reasoning_credit_charged")
    op.drop_constraint("ck_users_credits_non_negative", "users", type_="check")
    op.drop_column("users", "tts_method")
    op.drop_column("users", "credits")
