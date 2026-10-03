"""Initial independent Showcase schema."""

import sqlalchemy as sa
from alembic import op

revision = "202610030001"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "showcase_applications",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("name", sa.String(200), nullable=False),
        sa.Column("description", sa.Text(), nullable=False),
        sa.Column("github_url", sa.Text(), nullable=True),
        sa.PrimaryKeyConstraint("id", name="pk_showcase_applications"),
    )
    op.create_table(
        "showcase_media",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("application_id", sa.Uuid(), nullable=False),
        sa.Column("url", sa.Text(), nullable=False),
        sa.Column("display_order", sa.Integer(), nullable=False),
        sa.ForeignKeyConstraint(
            ["application_id"],
            ["showcase_applications.id"],
            name="fk_showcase_media_application_id_showcase_applications",
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name="pk_showcase_media"),
    )
    op.create_index(
        "ix_showcase_media_application_order", "showcase_media", ["application_id", "display_order"]
    )
    op.create_table(
        "showcase_releases",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("application_id", sa.Uuid(), nullable=False),
        sa.Column("version", sa.String(100), nullable=False),
        sa.Column("download_url", sa.Text(), nullable=False),
        sa.Column("published_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(
            ["application_id"],
            ["showcase_applications.id"],
            name="fk_showcase_releases_application_id_showcase_applications",
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name="pk_showcase_releases"),
    )
    op.create_index(
        "ix_showcase_releases_application_date",
        "showcase_releases",
        ["application_id", "published_at"],
    )


def downgrade() -> None:
    op.drop_table("showcase_releases")
    op.drop_table("showcase_media")
    op.drop_table("showcase_applications")
