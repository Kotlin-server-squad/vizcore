"""Runs every demo in the catalog and measures whether a person could follow it in the UI.

For each demo: create a session, start the demo the way the UI does
(POST /api/scenarios/{id}?sessionId=...), then poll the session's events until
they stop growing. Report the scripted duration (first to last event), how many
distinct half-second moments carry events, the share of events in the busiest
half second, and the coroutine count.

How to run, against a local backend at normal speed and a frontend that proxies to it:

    (cd backend && PORT=18080 DEMO_TIME_SCALE=1 ./gradlew :run -q) &
    (cd frontend && VITE_PROXY_TARGET=http://localhost:18080 pnpm dev --port 13080 --strictPort) &
    python3 -I scripts/demo-pacing-audit.py http://localhost:13080 /tmp/pacing.json channel-rendezvous,channel-buffered

Arguments: the frontend (or backend) base URL, the output JSON file, and optionally a comma-separated
list of demo ids (default: every demo in the catalog). New sessions are started 7 s apart (the server
allows 10 per minute) and at most 3 demos run at once. Compare duration_s, moments and busiest_share
with scripts/demo-pacing-baseline.json.
"""
import json
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:13291"
OUT = sys.argv[2] if len(sys.argv) > 2 else "demo-pacing.json"
IDLE_S = 4.0      # events unchanged this long = demo finished
CAP_S = 70.0      # the server stops demos at 60 s
BUCKET_NS = 500_000_000


def call(method, path, timeout=90):
    req = urllib.request.Request(BASE + path, method=method, data=b"" if method == "POST" else None)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            body = r.read().decode()
            return r.status, (json.loads(body) if body.strip().startswith(("{", "[")) else body)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:300]


CREATE_LOCK = threading.Lock()
CREATE_GAP_S = 7.0  # the server allows 10 session creates per minute
_last_create = [0.0]


def create_session(did):
    for _ in range(10):
        with CREATE_LOCK:
            wait = _last_create[0] + CREATE_GAP_S - time.time()
            if wait > 0:
                time.sleep(wait)
            _last_create[0] = time.time()
            status, s = call("POST", f"/api/sessions?name=audit-{did.replace('/', '-')}")
        if status != 429:
            return status, s
        time.sleep(15)
    return status, s


def run(demo):
    did = demo["id"]
    status, s = create_session(did)
    if status != 201:
        return {"id": did, "error": f"session create {status}: {s}"}
    sid = s["sessionId"]
    result = {}

    def start():
        for _ in range(30):
            st, body = call("POST", f"/api/scenarios/{did}?sessionId={sid}")
            if st != 503:
                result["start"] = (st, body if isinstance(body, str) else "")
                return
            time.sleep(5)
        result["start"] = (503, "busy for too long")

    t = threading.Thread(target=start, daemon=True)
    t.start()
    began = time.time()
    last_count, last_change = -1, time.time()
    events = []
    while time.time() - began < CAP_S:
        time.sleep(1)
        st, ev = call("GET", f"/api/sessions/{sid}/events")
        if st != 200 or not isinstance(ev, list):
            continue
        events = ev
        if len(ev) != last_count:
            last_count, last_change = len(ev), time.time()
        elif ev and time.time() - last_change >= IDLE_S and not t.is_alive():
            break
    t.join(timeout=5)
    call("DELETE", f"/api/sessions/{sid}")

    out = {"id": did, "group": demo.get("groupLabel"), "name": demo.get("name"), "start": result.get("start", ("?", ""))[0]}
    if not events:
        out["error"] = f"no events (start {out['start']} {result.get('start', ('', ''))[1][:120]})"
        return out
    ts = sorted(e["tsNanos"] for e in events if "tsNanos" in e)
    buckets = {}
    for x in ts:
        b = (x - ts[0]) // BUCKET_NS
        buckets[b] = buckets.get(b, 0) + 1
    coroutines = {e.get("coroutineId") for e in events if e.get("type") == "CoroutineCreated"}
    out.update(
        events=len(events),
        coroutines=len(coroutines),
        duration_s=round((ts[-1] - ts[0]) / 1e9, 1),
        moments=len(buckets),
        busiest_share=round(max(buckets.values()) / len(ts), 2),
        hit_cap=time.time() - began >= CAP_S,
    )
    return out


def main():
    status, cat = call("GET", "/api/scenarios")
    demos = cat["scenarios"]
    only = set(sys.argv[3].split(",")) if len(sys.argv) > 3 else None
    if only:
        demos = [d for d in demos if d["id"] in only]
    print(f"{len(demos)} demos from {BASE}", flush=True)
    results = []
    with ThreadPoolExecutor(max_workers=3) as pool:  # the server allows 3 demos at once
        for r in pool.map(run, demos):
            results.append(r)
            print(json.dumps(r), flush=True)
    json.dump(results, open(OUT, "w"), indent=2)
    print("DONE", flush=True)


if __name__ == "__main__":
    main()
