#!/usr/bin/env bash
# Pull a sample of seeded account ids from the SUT Postgres into the accounts.json the k6
# scenarios read. Run it once the consumer has drained the 100k seed, on the SUT or anywhere
# that can reach its Postgres. The uniform sample feeds the steady, spike and warm-up runs;
# the hot-account skew scenario draws its small hot set from the first ids of the same file.
set -euo pipefail

SAMPLE_SIZE="${SAMPLE_SIZE:-10000}"
PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"
PGDATABASE="${PGDATABASE:-transaction_authorizer}"
PGUSER="${PGUSER:-authorizer}"
export PGPASSWORD="${PGPASSWORD:-authorizer}"
OUT="${OUT:-accounts.json}"

# json_agg over a randomly ordered sample yields exactly the ["id", ...] array SharedArray
# expects, so no reshaping is needed on the k6 side.
psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -tA -c \
  "SELECT json_agg(id) FROM (SELECT id FROM accounts ORDER BY random() LIMIT ${SAMPLE_SIZE}) s;" \
  > "$OUT"

if ! grep -q '"' "$OUT"; then
  echo "extract-accounts: no ids written to $OUT; has the seed drained yet?" >&2
  exit 1
fi

echo "extract-accounts: wrote up to ${SAMPLE_SIZE} ids to $OUT"
