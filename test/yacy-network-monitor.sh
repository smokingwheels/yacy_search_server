#!/usr/bin/env bash
#
# yacy-network-monitor.sh
#
# Records local YaCy crawler statistics and, when anonymously accessible,
# selected public yacystats.de Grafana values.
#
# Usage:
#   chmod +x yacy-network-monitor.sh
#   ./yacy-network-monitor.sh
#
# Optional:
#   YACY_BASE_URL=http://127.0.0.1:8092
#   YACY_NAME=agent-regisas-ufe-55
#   INTERVAL=300
#   COUNT=0                 # 0 = run continuously
#   OUTPUT=yacy-network.csv
#   YACYSTATS_URL=https://yacystats.de
#   GRAFANA_DASHBOARD_UID=...
#
# Stop with Ctrl-C.
#
set -u

YACY_BASE_URL="${YACY_BASE_URL:-http://127.0.0.1:8090}"
YACY_BASE_URL="${YACY_BASE_URL%/}"
YACY_NAME="${YACY_NAME:-local-peer}"
INTERVAL="${INTERVAL:-300}"
COUNT="${COUNT:-0}"
OUTPUT="${OUTPUT:-yacy-network.csv}"
YACYSTATS_URL="${YACYSTATS_URL:-https://yacystats.de}"
YACYSTATS_URL="${YACYSTATS_URL%/}"
GRAFANA_DASHBOARD_UID="${GRAFANA_DASHBOARD_UID:-}"
HTTP_TIMEOUT="${HTTP_TIMEOUT:-20}"
STATE_DIR="${STATE_DIR:-.yacy-network-monitor}"

mkdir -p "$STATE_DIR"

need() {
    command -v "$1" >/dev/null 2>&1 || {
        echo "ERROR: required command not found: $1" >&2
        exit 2
    }
}

need curl
need awk
need sed
need date

if ! command -v python3 >/dev/null 2>&1; then
    echo "ERROR: python3 is required for XML/JSON parsing." >&2
    exit 2
fi

valid_uint() {
    case "$1" in
        ''|*[!0-9]*) return 1 ;;
        *) return 0 ;;
    esac
}

valid_uint "$INTERVAL" || {
    echo "ERROR: INTERVAL must be a whole number of seconds." >&2
    exit 2
}
valid_uint "$COUNT" || {
    echo "ERROR: COUNT must be a whole number." >&2
    exit 2
}

csv_escape() {
    local value="${1:-}"
    value=${value//\"/\"\"}
    printf '"%s"' "$value"
}

fetch() {
    curl --silent --show-error --location \
        --connect-timeout 8 --max-time "$HTTP_TIMEOUT" "$@"
}

discover_dashboard_uid() {
    local search_file="$STATE_DIR/grafana-search.json"

    fetch "$YACYSTATS_URL/api/search?type=dash-db&limit=100" \
        > "$search_file" 2>/dev/null || return 1

    python3 - "$search_file" <<'PY'
import json, sys
try:
    data = json.load(open(sys.argv[1], encoding="utf-8"))
except Exception:
    raise SystemExit(1)

preferred = []
others = []
for item in data if isinstance(data, list) else []:
    uid = item.get("uid")
    title = str(item.get("title", ""))
    if not uid:
        continue
    text = title.lower()
    if "yacy" in text or "site statistics" in text or "network" in text:
        preferred.append(uid)
    else:
        others.append(uid)

if preferred:
    print(preferred[0])
elif others:
    print(others[0])
else:
    raise SystemExit(1)
PY
}

load_dashboard() {
    local uid="$1"
    local out="$STATE_DIR/grafana-dashboard.json"
    fetch "$YACYSTATS_URL/api/dashboards/uid/$uid" > "$out" 2>/dev/null
}

extract_global_from_dashboard() {
    # Query public Grafana panels through /api/ds/query when supported.
    # Output fields:
    # peers_online,indexing_ppm,queries_per_minute,links_stored,remote_crawl_links
    local dashboard_file="$STATE_DIR/grafana-dashboard.json"
    local request_file="$STATE_DIR/grafana-query.json"
    local response_file="$STATE_DIR/grafana-query-response.json"
    local now_ms from_ms

    now_ms=$(date +%s000)
    from_ms=$((now_ms - 15 * 60 * 1000))

    python3 - "$dashboard_file" "$request_file" "$from_ms" "$now_ms" <<'PY'
import json, re, sys

src, dst, frm, to = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
try:
    root = json.load(open(src, encoding="utf-8"))
except Exception:
    raise SystemExit(1)

dashboard = root.get("dashboard", {})
panels = []

def walk(items):
    for p in items or []:
        if isinstance(p, dict):
            panels.append(p)
            walk(p.get("panels"))

walk(dashboard.get("panels"))

wanted = {
    "peers_online": ("peers online",),
    "indexing_ppm": ("indexing speed",),
    "queries_per_minute": ("queries / minute", "queries per minute"),
    "links_stored": ("links stored",),
    "remote_crawl_links": ("links for remote", "remote crawl"),
}

queries = []
meta = {}
refno = 0

for key, names in wanted.items():
    selected = None
    for panel in panels:
        title = str(panel.get("title", "")).lower()
        if any(name in title for name in names):
            selected = panel
            break
    if not selected:
        continue

    ds = selected.get("datasource")
    panel_ds_uid = ds.get("uid") if isinstance(ds, dict) else None

    for target in selected.get("targets", []) or []:
        if target.get("hide"):
            continue
        query = dict(target)
        refno += 1
        refid = f"M{refno}"
        query["refId"] = refid

        target_ds = query.get("datasource")
        ds_uid = (
            target_ds.get("uid") if isinstance(target_ds, dict)
            else panel_ds_uid
        )
        if not ds_uid:
            continue

        query["datasource"] = {"uid": ds_uid}
        queries.append(query)
        meta[refid] = key
        break

if not queries:
    raise SystemExit(1)

payload = {
    "from": str(frm),
    "to": str(to),
    "queries": queries,
}
json.dump(payload, open(dst, "w", encoding="utf-8"))
json.dump(meta, open(dst + ".meta", "w", encoding="utf-8"))
PY

    fetch -X POST \
        -H 'Content-Type: application/json' \
        --data-binary "@$request_file" \
        "$YACYSTATS_URL/api/ds/query" \
        > "$response_file" 2>/dev/null || return 1

    python3 - "$response_file" "$request_file.meta" <<'PY'
import json, math, sys

try:
    data = json.load(open(sys.argv[1], encoding="utf-8"))
    meta = json.load(open(sys.argv[2], encoding="utf-8"))
except Exception:
    raise SystemExit(1)

values = {
    "peers_online": "",
    "indexing_ppm": "",
    "queries_per_minute": "",
    "links_stored": "",
    "remote_crawl_links": "",
}

def last_number(obj):
    found = []
    def visit(x):
        if isinstance(x, dict):
            for k, v in x.items():
                if k == "values" and isinstance(v, list):
                    for series in v:
                        if isinstance(series, list):
                            for n in series:
                                if isinstance(n, (int, float)) and not isinstance(n, bool):
                                    if math.isfinite(float(n)):
                                        found.append(n)
                visit(v)
        elif isinstance(x, list):
            for v in x:
                visit(v)
    visit(obj)
    return found[-1] if found else None

results = data.get("results", {})
for refid, key in meta.items():
    result = results.get(refid)
    n = last_number(result)
    if n is not None:
        values[key] = str(n)

print("|".join(values[k] for k in (
    "peers_online",
    "indexing_ppm",
    "queries_per_minute",
    "links_stored",
    "remote_crawl_links",
)))
PY
}

read_local_status() {
    local xml_file="$STATE_DIR/local-status.xml"
    fetch "$YACY_BASE_URL/api/status_p.xml" > "$xml_file" 2>/dev/null || return 1

    python3 - "$xml_file" <<'PY'
import sys
import xml.etree.ElementTree as ET

try:
    root = ET.parse(sys.argv[1]).getroot()
except Exception:
    raise SystemExit(1)

def text(path):
    node = root.find(path)
    return (node.text or "").strip() if node is not None else ""

vals = [
    text("ppm"),
    text("loaderqueue/size"),
    text("loaderqueue/max"),
    text("localcrawlerqueue/size"),
    text("localcrawlerqueue/state"),
    text("remotecrawlerqueue/size"),
    text("memory/used"),
    text("memory/total"),
    text("memory/max"),
    text("load"),
    text("traffic/crawler"),
    text("dbsize/urlpublictext"),
]
print("|".join(vals))
PY
}

write_header() {
    if [[ ! -s "$OUTPUT" ]]; then
        printf '%s\n' \
'timestamp,peer,local_ppm,loader_size,loader_max,local_queue,local_state,remote_queue,memory_used_bytes,memory_total_bytes,memory_max_bytes,system_load,crawler_traffic_bytes,documents,global_peers_online,global_indexing_ppm,global_queries_per_minute,global_links_stored,global_remote_crawl_links,global_status' \
            > "$OUTPUT"
    fi
}

append_row() {
    local timestamp="$1"
    local local_data="$2"
    local global_data="$3"
    local global_status="$4"

    IFS='|' read -r \
        local_ppm loader_size loader_max local_queue local_state remote_queue \
        memory_used memory_total memory_max system_load crawler_traffic documents \
        <<< "$local_data"

    IFS='|' read -r \
        global_peers global_ppm global_queries global_links global_remote \
        <<< "$global_data"

    {
        csv_escape "$timestamp"; printf ','
        csv_escape "$YACY_NAME"; printf ','
        csv_escape "$local_ppm"; printf ','
        csv_escape "$loader_size"; printf ','
        csv_escape "$loader_max"; printf ','
        csv_escape "$local_queue"; printf ','
        csv_escape "$local_state"; printf ','
        csv_escape "$remote_queue"; printf ','
        csv_escape "$memory_used"; printf ','
        csv_escape "$memory_total"; printf ','
        csv_escape "$memory_max"; printf ','
        csv_escape "$system_load"; printf ','
        csv_escape "$crawler_traffic"; printf ','
        csv_escape "$documents"; printf ','
        csv_escape "$global_peers"; printf ','
        csv_escape "$global_ppm"; printf ','
        csv_escape "$global_queries"; printf ','
        csv_escape "$global_links"; printf ','
        csv_escape "$global_remote"; printf ','
        csv_escape "$global_status"
        printf '\n'
    } >> "$OUTPUT"
}

write_header

if [[ -z "$GRAFANA_DASHBOARD_UID" ]]; then
    GRAFANA_DASHBOARD_UID="$(discover_dashboard_uid 2>/dev/null || true)"
fi

if [[ -n "$GRAFANA_DASHBOARD_UID" ]]; then
    echo "yacystats.de dashboard UID: $GRAFANA_DASHBOARD_UID"
else
    echo "NOTE: public Grafana dashboard API was not discoverable."
    echo "      Local monitoring will still work."
    echo "      Set GRAFANA_DASHBOARD_UID when known."
fi

echo "Writing: $OUTPUT"
echo "Interval: ${INTERVAL}s"
echo "YaCy: $YACY_BASE_URL"
echo "Press Ctrl-C to stop."
echo

iteration=0
while :; do
    timestamp="$(date --iso-8601=seconds 2>/dev/null || date '+%Y-%m-%dT%H:%M:%S%z')"

    local_data="$(read_local_status 2>/dev/null || true)"
    if [[ -z "$local_data" ]]; then
        local_data='|||||||||||'
        echo "$timestamp  local YaCy status unavailable"
    fi

    global_data='||||'
    global_status="unavailable"

    if [[ -n "$GRAFANA_DASHBOARD_UID" ]] && load_dashboard "$GRAFANA_DASHBOARD_UID"; then
        result="$(extract_global_from_dashboard 2>/dev/null || true)"
        if [[ -n "$result" ]]; then
            global_data="$result"
            global_status="ok"
        else
            global_status="dashboard-query-blocked-or-unsupported"
        fi
    fi

    append_row "$timestamp" "$local_data" "$global_data" "$global_status"

    IFS='|' read -r ppm loader loader_max queue _ <<< "$local_data"
    IFS='|' read -r peers global_ppm _ <<< "$global_data"

    printf '%s local=%s PPM loader=%s/%s queue=%s' \
        "$timestamp" "${ppm:-?}" "${loader:-?}" "${loader_max:-?}" "${queue:-?}"
    if [[ "$global_status" = "ok" ]]; then
        printf ' global=%s PPM peers=%s' "${global_ppm:-?}" "${peers:-?}"
    else
        printf ' global=%s' "$global_status"
    fi
    printf '\n'

    iteration=$((iteration + 1))
    if [[ "$COUNT" -gt 0 && "$iteration" -ge "$COUNT" ]]; then
        break
    fi

    sleep "$INTERVAL"
done
