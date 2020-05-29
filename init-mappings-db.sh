#!/bin/bash
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
CREATE TABLE IF NOT EXISTS mapping
(
    ega_id          VARCHAR NOT NULL,
    elixir_id       VARCHAR NOT NULL,
    PRIMARY KEY (ega_id),
    UNIQUE (ega_id, elixir_id)
);
EOSQL
