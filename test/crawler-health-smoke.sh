#!/usr/bin/env bash
# crawler-health.sh - quick YaCy crawler/JVM health snapshot
# Usage: ./crawler-health.sh [PID]
# Optional: YACY_HOME=/path/to/yacy SAMPLE_SECONDS=10 YACY_PORT=8090

set -u

SAMPLE_SECONDS="${SAMPLE_SECONDS:-10}"
YACY_HOME="${YACY_HOME:-$(pwd)}"
YACY_PORT="${YACY_PORT:-}"
timestamp="$(date '+%Y%m%d-%H%M%S')"
report_dir="${YACY_HOME}/crawler-health-${timestamp}"
mkdir -p "$report_dir" 2>/dev/null || {
    report_dir="/tmp/crawler-health-${timestamp}"
    mkdir -p "$report_dir"
}

log() { printf '%s\n' "$*" | tee -a "$report_dir/summary.txt"; }
have() { command -v "$1" >/dev/null 2>&1; }

find_pid() {
    if [[ $# -ge 1 && "$1" =~ ^[0-9]+$ ]] && kill -0 "$1" 2>/dev/null; then
        printf '%s\n' "$1"
        return
    fi
    if [[ -f "$YACY_HOME/DATA/yacy.running" ]]; then
        local p
        p="$(tr -dc '0-9' < "$YACY_HOME/DATA/yacy.running")"
        if [[ -n "$p" ]] && kill -0 "$p" 2>/dev/null; then
            printf '%s\n' "$p"
            return
        fi
    fi
    pgrep -f 'net\.yacy\.yacy|yacy\.jar' | head -n 1
}

PID="$(find_pid "${1:-}")"
if [[ -z "${PID:-}" ]] || ! kill -0 "$PID" 2>/dev/null; then
    echo "ERROR: YaCy Java PID not found."
    echo "Run: $0 <PID>"
    exit 1
fi

if [[ -z "$YACY_PORT" && -f "$YACY_HOME/DATA/SETTINGS/yacy.conf" ]]; then
    YACY_PORT="$(sed -n 's/^port=//p' "$YACY_HOME/DATA/SETTINGS/yacy.conf" | head -n 1)"
fi
YACY_PORT="${YACY_PORT:-8090}"
YACY_LOG="$YACY_HOME/DATA/LOG/yacy00.log"

log "YaCy crawler health report"
log "Time:       $(date --iso-8601=seconds 2>/dev/null || date)"
log "Host:       $(hostname)"
log "PID:        $PID"
log "YaCy home:  $YACY_HOME"
log "Port:       $YACY_PORT"
log "Sample:     ${SAMPLE_SECONDS}s"
log "Report:     $report_dir"
log ""

{
    echo "=== PROCESS ==="
    ps -p "$PID" -o pid,ppid,nlwp,%cpu,%mem,rss,vsz,etime,stat,cmd
    echo
    echo "=== TOP THREADS ==="
    ps -L -p "$PID" -o pid,tid,psr,stat,%cpu,time,comm --sort=-%cpu | head -n 25
} > "$report_dir/process.txt" 2>&1

if have jcmd; then
    jcmd "$PID" VM.version > "$report_dir/jvm-version.txt" 2>&1
    jcmd "$PID" VM.flags > "$report_dir/jvm-flags.txt" 2>&1
    jcmd "$PID" GC.heap_info > "$report_dir/heap.txt" 2>&1
    jcmd "$PID" Thread.print -l > "$report_dir/thread-dump.txt" 2>&1
elif have jstack; then
    jstack -l "$PID" > "$report_dir/thread-dump.txt" 2>&1
else
    echo "Neither jcmd nor jstack is installed." > "$report_dir/thread-dump.txt"
fi

THREAD_DUMP="$report_dir/thread-dump.txt"
blocked_total=0
hostqueue_hits=0
crawlstacker_hits=0
loader_waiting=0

if [[ -s "$THREAD_DUMP" ]]; then
    blocked_total="$(grep -c 'java.lang.Thread.State: BLOCKED' "$THREAD_DUMP" 2>/dev/null || true)"
    hostqueue_hits="$(grep -c 'net.yacy.crawler.HostQueue' "$THREAD_DUMP" 2>/dev/null || true)"
    crawlstacker_hits="$(grep -c '^"CrawlStacker_pool' "$THREAD_DUMP" 2>/dev/null || true)"
    loader_waiting="$(grep -c '^"CrawlQueues.Loader(WAITING)' "$THREAD_DUMP" 2>/dev/null || true)"

    grep -B 4 -A 18 -E \
        'java.lang.Thread.State: BLOCKED|HostQueue\.(push|has|pop)|HostBalancer\.(push|pop)|OnDemandOpenFileIndex\.has' \
        "$THREAD_DUMP" > "$report_dir/blocked-hostqueue.txt" 2>/dev/null || true

    awk '
        /^"/ { thread=$0 }
        /java.lang.Thread.State: BLOCKED/ { print thread; print; capture=14; next }
        capture > 0 { print; capture-- }
    ' "$THREAD_DUMP" > "$report_dir/blocked-threads.txt" 2>/dev/null || true
fi

{
    echo "=== SOCKET STATES FOR PID $PID ==="
    if have ss; then
        ss -tanp 2>/dev/null | grep "pid=$PID," |
            awk '{count[$1]++} END {for (s in count) printf "%8d %s\n", count[s], s}' | sort -nr
        echo
        echo "=== LISTENERS ==="
        ss -ltnp 2>/dev/null | grep "pid=$PID," || true
    elif have netstat; then
        netstat -tanp 2>/dev/null | grep "/java" |
            awk 'NR>2 {count[$6]++} END {for (s in count) printf "%8d %s\n", count[s], s}' | sort -nr
    else
        echo "ss/netstat unavailable"
    fi
} > "$report_dir/sockets.txt" 2>&1

{
    echo "Open file descriptors: $(find "/proc/$PID/fd" -mindepth 1 -maxdepth 1 2>/dev/null | wc -l)"
    echo
    [[ -r "/proc/$PID/limits" ]] && grep -E 'open files|max user processes' "/proc/$PID/limits" || true
} > "$report_dir/file-descriptors.txt"

sampler_pids=()
if have pidstat; then
    pidstat -p "$PID" -t 1 "$SAMPLE_SECONDS" > "$report_dir/pidstat.txt" 2>&1 & sampler_pids+=("$!")
fi
if have iostat; then
    iostat -xz 1 "$SAMPLE_SECONDS" > "$report_dir/iostat.txt" 2>&1 & sampler_pids+=("$!")
fi
if have vmstat; then
    vmstat 1 "$SAMPLE_SECONDS" > "$report_dir/vmstat.txt" 2>&1 & sampler_pids+=("$!")
fi

if have curl; then
    curl -fsS --max-time 10 "http://127.0.0.1:${YACY_PORT}/api/status_p.xml" \
        > "$report_dir/yacy-status.xml" 2> "$report_dir/yacy-status-error.txt" || true
    curl -fsS --max-time 10 "http://127.0.0.1:${YACY_PORT}/PerformanceQueues_p.html" \
        > "$report_dir/performance-queues.html" 2> "$report_dir/performance-queues-error.txt" || true
fi

if [[ -f "$YACY_LOG" ]]; then
    tail -n 5000 "$YACY_LOG" > "$report_dir/yacy-log-tail.txt"
    grep -E 'STACKING TIME|indexStorageTime|HostQueue|HostBalancer|LoaderDispatcher waited|CLOSE-WAIT|OutOfMemory|IndexWriter|blocked|queue' \
        "$report_dir/yacy-log-tail.txt" | tail -n 500 > "$report_dir/yacy-relevant-log.txt" || true
    grep 'STACKING TIME' "$report_dir/yacy-log-tail.txt" | tail -n 100 > "$report_dir/stacking-times.txt" || true
    grep 'indexStorageTime' "$report_dir/yacy-log-tail.txt" | tail -n 100 > "$report_dir/index-storage-times.txt" || true
else
    echo "YaCy log not found: $YACY_LOG" > "$report_dir/yacy-log-tail.txt"
fi

for sampler_pid in "${sampler_pids[@]}"; do wait "$sampler_pid" 2>/dev/null || true; done

loadavg="$(cut -d ' ' -f 1-3 /proc/loadavg 2>/dev/null || echo unknown)"
fd_count="$(find "/proc/$PID/fd" -mindepth 1 -maxdepth 1 2>/dev/null | wc -l)"

log "=== QUICK RESULT ==="
log "Load average:             $loadavg"
log "Open file descriptors:    $fd_count"
log "BLOCKED JVM threads:      $blocked_total"
log "CrawlStacker threads:     $crawlstacker_hits"
log "HostQueue stack entries:  $hostqueue_hits"
log "Loader WAITING threads:   $loader_waiting"

if [[ "$blocked_total" -gt 20 || "$hostqueue_hits" -gt 50 ]]; then
    log "Assessment: possible crawler/HostQueue contention."
elif [[ "$blocked_total" -gt 0 ]]; then
    log "Assessment: some blocked threads; inspect blocked-threads.txt."
else
    log "Assessment: no JVM BLOCKED threads captured."
fi

log ""
log "Finished. Files are in:"
log "$report_dir"

if have tar; then
    tar -czf "${report_dir}.tar.gz" -C "$(dirname "$report_dir")" "$(basename "$report_dir")" 2>/dev/null || true
    [[ -f "${report_dir}.tar.gz" ]] && { log "Archive:"; log "${report_dir}.tar.gz"; }
fi
