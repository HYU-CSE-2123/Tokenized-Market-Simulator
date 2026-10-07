#!/bin/sh
# A successful SQL connection over TCP, not the entrypoint's temporary Unix socket.
# Password is read locally, never part of Docker config/argv or health output.
set -eu
export PGCONNECT_TIMEOUT=2
export PGOPTIONS='-c statement_timeout=2000'
export PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")"
exec psql -h 127.0.0.1 -p 5432 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
    -X -q -t -A -v ON_ERROR_STOP=1 -c 'SELECT 1' >/dev/null 2>&1
