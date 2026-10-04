#!/bin/sh
set -eu

: "${SHOWCASE_DB:?SHOWCASE_DB is required}"
: "${SHOWCASE_USER:?SHOWCASE_USER is required}"
: "${SHOWCASE_PASSWORD:?SHOWCASE_PASSWORD is required}"

# Showcase must stay separate from the main Butler database/user.
if [ "$SHOWCASE_DB" = "$POSTGRES_DB" ]; then
    echo "SHOWCASE_DB must be different from POSTGRES_DB" >&2
    exit 1
fi

if [ "$SHOWCASE_USER" = "$POSTGRES_USER" ]; then
    echo "SHOWCASE_USER must be different from POSTGRES_USER" >&2
    exit 1
fi

# Create the Showcase role if it does not exist.
# If it already exists, update its password.
psql -v ON_ERROR_STOP=1 \
    --username "$POSTGRES_USER" \
    --dbname "$POSTGRES_DB" \
    --set=showcase_user="$SHOWCASE_USER" \
    --set=showcase_password="$SHOWCASE_PASSWORD" <<'SQL'

SELECT format(
    'CREATE ROLE %I LOGIN PASSWORD %L',
    :'showcase_user',
    :'showcase_password'
)
WHERE NOT EXISTS (
    SELECT 1
    FROM pg_roles
    WHERE rolname = :'showcase_user'
) \gexec

SELECT format(
    'ALTER ROLE %I WITH LOGIN PASSWORD %L',
    :'showcase_user',
    :'showcase_password'
) \gexec

SQL

# Create the Showcase database if it does not exist.
psql -v ON_ERROR_STOP=1 \
    --username "$POSTGRES_USER" \
    --dbname "$POSTGRES_DB" \
    --set=showcase_db="$SHOWCASE_DB" \
    --set=showcase_user="$SHOWCASE_USER" <<'SQL'

SELECT format(
    'CREATE DATABASE %I OWNER %I',
    :'showcase_db',
    :'showcase_user'
)
WHERE NOT EXISTS (
    SELECT 1
    FROM pg_database
    WHERE datname = :'showcase_db'
) \gexec

SQL

# The DB may already exist from an older version of this script,
# so make sure the Showcase role owns it.
psql -v ON_ERROR_STOP=1 \
    --username "$POSTGRES_USER" \
    --dbname "$POSTGRES_DB" \
    --set=showcase_db="$SHOWCASE_DB" \
    --set=showcase_user="$SHOWCASE_USER" <<'SQL'

SELECT format(
    'ALTER DATABASE %I OWNER TO %I',
    :'showcase_db',
    :'showcase_user'
) \gexec

SQL

echo "Showcase database '$SHOWCASE_DB' is ready for user '$SHOWCASE_USER'."