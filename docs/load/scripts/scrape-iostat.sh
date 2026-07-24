#!/usr/bin/env bash
# Sample the SUT disk counters at a fixed interval into a CSV during a run, so the claim that
# the measured ceiling is the connection pool and not storage rests on data instead of on an
# assertion. Start it just before a scenario, stop it (Ctrl-C) when the run ends. The write
# latency reported per run is summarized over these samples, so the interval is what defines
# what a percentile over them means.
set -euo pipefail

DEV="${DEV:-nvme0n1}"
INTERVAL="${INTERVAL:-1}"
OUT="${OUT:-iostat.csv}"

echo "epoch,writes_per_s,w_await_ms,queue_depth,util_pct" > "$OUT"
echo "scrape-iostat: sampling $DEV every ${INTERVAL}s into $OUT (Ctrl-C to stop)" >&2

# -y drops the since-boot first report, which would otherwise average the whole uptime into
# the first sample. Column positions move between sysstat versions, so the header line is what
# maps a name to an index rather than a hardcoded field number.
iostat -x -y "$INTERVAL" "$DEV" |
  awk -v dev="$DEV" -v out="$OUT" '
    /^Device/ { for (i = 1; i <= NF; i++) col[$i] = i; next }
    $1 == dev && ("w/s" in col) {
      printf "%d,%s,%s,%s,%s\n", systime(), $(col["w/s"]), $(col["w_await"]), $(col["aqu-sz"]), $(col["%util"]) >> out
      fflush(out)
    }
  '
