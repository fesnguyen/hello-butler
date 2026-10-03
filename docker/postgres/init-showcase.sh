#!/bin/sh
set -eu
: "${SHOWCASE_DB:?Set SHOWCASE_DB to showcase_dev or showcase}"
if [ "$SHOWCASE_DB" = "$POSTGRES_DB" ]; then
    echo "Showcase must use a separate database" >&2
    exit 1
fi
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    --set=showcase_db="$SHOWCASE_DB" <<'SQL'
SELECT format('CREATE DATABASE %I', :'showcase_db')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'showcase_db') \gexec
SQL
