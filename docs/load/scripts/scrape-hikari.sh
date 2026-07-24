#!/usr/bin/env bash
# Sample the HikariCP connection-pool gauges from the SUT Prometheus endpoint at a fixed
# interval into a CSV, so the pool-saturation graph over a run has a data source. Start it
# just before a scenario, stop it (Ctrl-C) when the run ends. Under virtual threads the pool,
# not a thread count, is the concurrency ceiling, so active against max with a rising pending
# is the graph that shows the designed limit being reached.
set -euo pipefail

URL="${PROM_URL:-http://localhost:8080/actuator/prometheus}"
INTERVAL="${INTERVAL:-1}"
OUT="${OUT:-hikari.csv}"

gauge() { awk -v m="$1" '$0 ~ "^" m "\\{" {print $2; exit}'; }

echo "epoch,active,idle,pending,max" > "$OUT"
echo "scrape-hikari: sampling $URL every ${INTERVAL}s into $OUT (Ctrl-C to stop)" >&2

while true; do
  if body="$(curl -fsS "$URL")"; then
    ts="$(date +%s)"
    active="$(printf '%s\n' "$body" | gauge hikaricp_connections_active)"
    idle="$(printf '%s\n' "$body" | gauge hikaricp_connections_idle)"
    pending="$(printf '%s\n' "$body" | gauge hikaricp_connections_pending)"
    max="$(printf '%s\n' "$body" | gauge hikaricp_connections_max)"
    echo "${ts},${active:-},${idle:-},${pending:-},${max:-}" >> "$OUT"
  fi
  sleep "$INTERVAL"
done
