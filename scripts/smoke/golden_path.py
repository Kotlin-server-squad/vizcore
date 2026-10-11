"""Golden-path smoke test against a running vizcore backend in memory mode (#133).

Adapted from the local UAT script that verified the run-1 fixes against a live server.
`scripts/smoke/run-smoke.sh` boots the fat jar, waits for `/api/ready` and runs this; run it
directly only against a server you started yourself. The target is read from `SMOKE_PORT`
(default 18080) on 127.0.0.1.

Checks, in this order — the rate-limit burst runs LAST so it cannot throttle the others:

  A  SSE exactly-once under concurrent emission (#138): one stream opened before emission
     and one opened mid-emission see every stored seq, exactly once.
  B  ingest from a restarted client (#127): duplicate, decreasing and Long.MAX_VALUE client
     seqs are all kept, the stored seq stays strictly increasing, and every stored event
     carries the server-resolved session id even though the frames spoof one.
  C  concurrent reads during writes (#135): readers on five projections see no non-2xx.
  D  rate-limit policy (#124): 600 read GETs with no 429, then a session create.

Exit code is 0 only when every check passed; a failing or crashing check exits 1.
"""
import asyncio
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

import websockets

PORT = os.environ.get("SMOKE_PORT", "18080")
B = f"http://127.0.0.1:{PORT}"
WS = f"ws://127.0.0.1:{PORT}"

# rateLimit.read.requestsPerMinute from backend/src/main/resources/application.yaml: the
# bucket every GET below draws on. Readers stay under it; only check D deliberately bursts.
READ_RPM = 1200
# The RateLimit refillPeriod is one minute, so the read bucket is full again this long after
# the last GET. Check D's "no 429 in a 600-read burst" only means anything against a bucket
# that check C has not just drained, so wait it out rather than race it.
READ_BUCKET_REFILL_S = 61
# Marks the events this script ingests, so they can be told apart from scenario output.
INGEST_SCOPE = "scope-smoke"

results = []


def http(method, path, timeout=30):
    req = urllib.request.Request(B + path, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read(), dict(r.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read(), dict(e.headers)


def new_session(name):
    st, body, _ = http("POST", f"/api/sessions?name={name}")
    if st >= 300:
        raise RuntimeError(f"POST /api/sessions?name={name} returned HTTP {st}: {body[:200]!r}")
    return json.loads(body)["sessionId"]


def events(sid):
    st, body, _ = http("GET", f"/api/sessions/{sid}/events")
    if st >= 300:
        raise RuntimeError(f"GET /api/sessions/{sid}/events returned HTTP {st}")
    return json.loads(body)


def report(name, ok, detail):
    results.append((name, ok, detail))
    print(f"[{'PASS' if ok else 'FAIL'}] {name}: {detail}", flush=True)


class Sse(threading.Thread):
    """Collects event seqs from /stream until the process exits."""

    def __init__(self, sid):
        super().__init__(daemon=True)
        self.sid, self.seqs, self.ready = sid, [], threading.Event()
        self.error = None

    def run(self):
        try:
            r = urllib.request.urlopen(B + f"/api/sessions/{self.sid}/stream", timeout=120)
            for raw in r:
                line = raw.decode().rstrip("\n")
                if line.startswith(": connected"):
                    self.ready.set()
                if line.startswith("data: "):
                    try:
                        d = json.loads(line[6:])
                    except ValueError:
                        continue
                    # A frame is one event object or, for `event: batch`, a JSON array.
                    # Frames without a seq (e.g. heartbeats) are not stored events: skip them.
                    self.seqs.extend(
                        e["seq"] for e in (d if isinstance(d, list) else [d])
                        if isinstance(e, dict) and e.get("seq") is not None
                    )
        except Exception as e:  # noqa: BLE001 - surfaced by the check that opened the stream
            self.error = e


def monotonic(seqs):
    return all(b > a for a, b in zip(seqs, seqs[1:]))


def wait_stable_events(sid, timeout=30.0, quiet=1.0):
    """Poll /events until its length stops growing, so asserts see the final store."""
    deadline = time.monotonic() + timeout
    prev, ev = -1, events(sid)
    while time.monotonic() < deadline:
        if len(ev) == prev:
            return ev
        prev = len(ev)
        time.sleep(quiet)
        ev = events(sid)
    return ev


def wait_delivered(collector, want, timeout=20.0):
    """Wait until an SSE collector has seen every stored seq (bounded)."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline and not want <= set(collector.seqs):
        time.sleep(0.25)
    return want <= set(collector.seqs)


# --- A #138 SSE: streams opened BEFORE and DURING emission vs the store ------------------
SSE_SCENARIOS = ["parallel", "deep-nesting", "mixed", "channel-fan-out"]


def check_a_sse_exactly_once():
    sid = new_session("smoke-sse")
    early = Sse(sid)
    early.start()
    if not early.ready.wait(15):
        report("A #138 SSE stream ready", False, f"no ': connected' in 15s (error={early.error!r})")
        return
    with ThreadPoolExecutor(len(SSE_SCENARIOS)) as ex:
        futs = [
            ex.submit(http, "POST", f"/api/scenarios/{s}?sessionId={sid}", 120)
            for s in SSE_SCENARIOS
        ]
        time.sleep(0.05)
        late = Sse(sid)  # subscribes mid-emission: replay snapshot + live
        late.start()
        codes = [f.result()[0] for f in futs]
    bad = [c for c in codes if c >= 300]
    report(
        "A #138 4 concurrent scenarios accepted", not bad,
        f"codes={codes}",
    )
    store = [e["seq"] for e in wait_stable_events(sid)]
    want = set(store)
    report(
        "A #127 store seqs strictly increasing", monotonic(store) and len(want) == len(store),
        f"{len(store)} events, {len(want)} distinct seqs",
    )
    for label, s in [("early (before emission)", early), ("late (mid-emission)", late)]:
        wait_delivered(s, want)
        seqs = list(s.seqs)
        missing = sorted(want - set(seqs))
        dups = len(seqs) - len(set(seqs))
        # Live delivery is lifecycle-first by design, so assert exactly-once, never order.
        report(
            f"A #138 SSE {label} exactly-once", not missing and dups == 0,
            f"store={len(store)} sse={len(seqs)} missing={len(missing)} dups={dups}"
            f" error={s.error!r}",
        )


# --- B #127 ingest: a restarted client resends seqs, plus junk seqs -----------------
INGEST_BATCHES = [
    (list(range(1, 101)), "run1"),             # first client run
    (list(range(1, 101)), "run2"),             # restarted client resends seq 1..100
    ([50, 40, 40, 2**63 - 1, 7], "junk"),      # decreasing, duplicate, Long.MAX_VALUE
    (list(range(1, 6)), "after-max"),          # still accepted after MAX
]


async def ingest(sid, seqs, tag):
    """One ingest WebSocket connection; a new one per batch models a client restart."""
    async with websockets.connect(f"{WS}/api/sessions/{sid}/ingest") as ws:
        for i, seq in enumerate(seqs):
            await ws.send(json.dumps({
                "type": "CoroutineCreated", "sessionId": "spoofed", "seq": seq,
                "tsNanos": time.monotonic_ns(), "coroutineId": f"{tag}-{i}",
                "jobId": f"job-{tag}-{i}", "parentCoroutineId": None,
                "scopeId": INGEST_SCOPE, "label": f"{tag}-{i}",
                "createdAtEpochMs": int(time.time() * 1000), "creationPoint": None,
            }))
        await asyncio.sleep(0.5)


def check_b_ingest_restart():
    sid = new_session("smoke-ingest")
    expected = sum(len(seqs) for seqs, _ in INGEST_BATCHES)
    for seqs, tag in INGEST_BATCHES:
        asyncio.run(ingest(sid, seqs, tag))
    # Server-side emission interleaved with the ingested stream, so the monotonic assert
    # below covers both producers writing into one session.
    http("POST", f"/api/scenarios/nested?sessionId={sid}", 120)
    ev = wait_stable_events(sid)
    ingested = [e for e in ev if e.get("scopeId") == INGEST_SCOPE]
    seqs = [e["seq"] for e in ev]
    report(
        f"B #127 all {expected} ingested events kept", len(ingested) == expected,
        f"kept={len(ingested)} of {expected} ({len(ev)} events in session)",
    )
    report(
        "B #127 stored seq strictly increasing, no client seq kept",
        monotonic(seqs) and max(seqs) < 2**62,
        f"events={len(seqs)} max_seq={max(seqs)}",
    )
    spoofed = sum(e["sessionId"] != sid for e in ev)
    report(
        "B stored events carry the server-resolved session id", spoofed == 0,
        f"{spoofed} of {len(ev)} stored events do not carry the target session id",
    )


# --- C #135 concurrent readers while scenarios write -------------------------------------
WRITE_SCENARIOS = [
    "parallel", "dispatcher", "deep-nesting", "mixed", "order-processing", "channel-buffered",
]
# 5 readers at this interval is ~750 reads/min, comfortably under the READ_RPM bucket, so a
# 429 here would be a real regression and not this check overrunning its own budget.
READER_INTERVAL_S = 0.4


def check_c_concurrent_reads():
    sid = new_session("smoke-concurrent")
    stop = threading.Event()
    bad, count = [], [0]

    def reader(path):
        while not stop.is_set():
            st = http("GET", path)[0]
            count[0] += 1
            if st >= 300:
                bad.append((path, st))
            time.sleep(READER_INTERVAL_S)

    paths = [
        f"/api/sessions/{sid}", f"/api/sessions/{sid}/threads", "/api/sessions",
        f"/api/sessions/{sid}/hierarchy", f"/api/sessions/{sid}/metrics",
    ]
    threads = [threading.Thread(target=reader, args=(p,), daemon=True) for p in paths]
    for t in threads:
        t.start()
    with ThreadPoolExecutor(6) as ex:
        codes = list(ex.map(
            lambda s: http("POST", f"/api/scenarios/{s}?sessionId={sid}", 120)[0],
            WRITE_SCENARIOS * 2,
        ))
    stop.set()
    for t in threads:
        t.join()
    writes_ok = [c for c in codes if c >= 300]
    report(
        "C #135 concurrent reads during writes, no non-2xx",
        not bad and not writes_ok and count[0] > 20,
        f"{count[0]} reads, {len(events(sid))} events written,"
        f" bad_reads={bad[:3]} bad_writes={writes_ok[:3]}",
    )


# --- D #124 rate limit: 600 reads in a burst, then a session create ----------------------
READ_BURST = 600


def check_d_rate_limit():
    # Give the read bucket its refill period before bursting (see READ_BUCKET_REFILL_S).
    print(f"... waiting {READ_BUCKET_REFILL_S}s for the read bucket to refill", flush=True)
    time.sleep(READ_BUCKET_REFILL_S)
    with ThreadPoolExecutor(20) as ex:
        codes = list(ex.map(lambda _: http("GET", "/api/sessions")[0], range(READ_BURST)))
    n429 = codes.count(429)
    other = sum(1 for c in codes if c >= 300 and c != 429)
    report(
        f"D #124 {READ_BURST} read GETs, no 429", n429 == 0 and other == 0,
        f"{len(codes)} requests, {n429}x429, other non-2xx={other}",
    )
    st, _, _ = http("POST", "/api/sessions?name=smoke-after-burst&correlation=smoke-corr")
    report("D #124 session create after read burst", st < 300, f"HTTP {st}")


CHECKS = [
    check_a_sse_exactly_once,
    check_b_ingest_restart,
    check_c_concurrent_reads,
    check_d_rate_limit,  # last: its burst must not throttle the checks above
]

if __name__ == "__main__":
    crashed = 0
    for check in CHECKS:
        try:
            check()
        except Exception as e:  # noqa: BLE001 - a crashed check is a failed smoke run
            crashed += 1
            report(check.__name__, False, f"crashed: {e!r}")
    passed = sum(ok for _, ok, _ in results)
    print(f"\n{passed}/{len(results)} assertions passed, {crashed} check(s) crashed", flush=True)
    failed = [name for name, ok, _ in results if not ok]
    if failed or crashed or not results:
        print(f"SMOKE FAILED: {failed}", flush=True)
        sys.exit(1)
    print("SMOKE PASSED", flush=True)
