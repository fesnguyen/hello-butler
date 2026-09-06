"""morning brief planning ownership

Revision ID: 202609050001
Revises: 202609030002
Create Date: 2026-09-05 00:01:00.000000
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "202609050001"
down_revision: str | None = "202609030002"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "daily_events",
        sa.Column("origin", sa.String(length=40), server_default="user", nullable=False),
    )
    op.add_column("daily_events", sa.Column("planner_key", sa.String(length=120), nullable=True))
    op.create_unique_constraint(
        op.f("uq_daily_events_user_id"),
        "daily_events",
        ["user_id", "event_date", "planner_key"],
    )


def downgrade() -> None:
    op.drop_constraint(op.f("uq_daily_events_user_id"), "daily_events", type_="unique")
    op.drop_column("daily_events", "planner_key")
    op.drop_column("daily_events", "origin")
