#!/usr/bin/env python3
"""
End-to-end webhook test. Needs PayCore running (docker compose up) on localhost:8080.
This script starts its own tiny webhook receivers on ports 9000 and 9999 on YOUR machine; the app
(inside Docker) reaches them through host.docker.internal.
If Windows Firewall asks about Python, click Allow.

    python scripts/webhook_test.py          (takes about 30-60 seconds)
"""
import hashlib
import hmac
import json
import os
import threading
import time
import urllib.error
import urllib.request
import uuid
from collections import defaultdict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BASE = os.environ.get("BASE_URL", "http://localhost:8080")
ADMIN = {"X-Admin-Key": os.environ.get("ADMIN_KEY", "dev-admin-key")}
APP_TO_HOST = os.environ.get("RECEIVER_HOST", "host.docker.internal")  # how the *app* reaches this machine
results = []


# ---------------------------------------------------------------- receiver
class Receiver:
    """A fake merchant server. Verifies signatures and can be told to fail the first N deliveries."""

    def __init__(self, port, fail_first=0):
        self.fail_first = fail_first
        self.secret = ""
        self.lock = threading.Lock()
        self.hits = defaultdict(int)   # event id -> how many times it was delivered to us
        self.accepted = {}             # event id -> event type (answered 200)
        self.bad_signatures = 0
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
                valid = outer.verify(self.headers.get("X-PayCore-Signature", ""), body)
                code = 200
                with outer.lock:
                    if not valid:
                        outer.bad_signatures += 1
                        code = 400
                    else:
                        event = json.loads(body)
                        outer.hits[event["id"]] += 1
                        if outer.hits[event["id"]] <= outer.fail_first:
                            code = 500
                        else:
                            outer.accepted[event["id"]] = event["type"]
                self.send_response(code)
                self.send_header("Content-Length", "0")
                self.end_headers()

        self.server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def verify(self, header, body):
        parts = dict(p.split("=", 1) for p in header.split(",") if "=" in p)
        ts, v1 = parts.get("t"), parts.get("v1")
        if not ts or not v1:
            return False
        expected = hmac.new(self.secret.encode(), ts.encode() + b"." + body, hashlib.sha256).hexdigest()
        return hmac.compare_digest(expected, v1) and abs(time.time() - int(ts)) < 300


# ---------------------------------------------------------------- helpers
def raw(method, path, body=None, key=None, headers=None):
    data = json.dumps(body).encode() if body is not None else (b"" if method == "POST" else None)
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if key:
        req.add_header("Idempotency-Key", key)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def call(method, path, body=None, key=None, headers=None):
    status, payload = raw(method, path, body, key, headers)
    try:
        return status, (json.loads(payload) if payload else None)
    except ValueError:
        return status, None


def check(name, ok, detail=""):
    results.append(ok)
    print(f"[{'PASS' if ok else 'FAIL'}] {name} {detail}")


def mh(api_key):
    return {"Authorization": f"Bearer {api_key}"}


def new_account(name, type_):
    return call("POST", "/accounts", {"name": name, "type": type_}, headers=ADMIN)[1]["id"]


def new_merchant(name):
    mid = new_account(name, "MERCHANT")
    _, body = call("POST", f"/admin/merchants/{mid}/api-keys", {"name": "test"}, headers=ADMIN)
    return mid, body["apiKey"]


def pay(customer, merchant, mkey, amount=100):
    return call("POST", "/payments", {"customerId": customer, "merchantId": merchant, "amount": amount},
                key=str(uuid.uuid4()), headers=mh(mkey))[1]["id"]


def act(pid, action, mkey):
    return call("POST", f"/payments/{pid}/{action}", key=str(uuid.uuid4()), headers=mh(mkey))


def events(mkey, status=None):
    q = f"?status={status}" if status else ""
    return call("GET", f"/webhooks/events{q}", headers=mh(mkey))[1] or []


def wait_for(cond, timeout):
    end = time.time() + timeout
    while time.time() < end:
        if cond():
            return True
        time.sleep(0.5)
    return cond()


# ---------------------------------------------------------------- tests
def main():
    # ===== merchant 1: happy path with flaky receiver (first 2 deliveries of each event fail with 500)
    rcv = Receiver(9000, fail_first=2)
    customer = new_account("alice", "CUSTOMER")
    merchant, mkey = new_merchant("hook-shop")
    call("POST", f"/accounts/{customer}/deposit", {"amount": 1000}, key=str(uuid.uuid4()), headers=ADMIN)

    code, ep = call("POST", "/webhooks", {"url": f"http://{APP_TO_HOST}:9000/hook"}, headers=mh(mkey))
    check("1.  register webhook endpoint -> 201 + signing secret",
          code == 201 and ep and ep["secret"].startswith("whsec_"), f"(got {code})")
    if code != 201:
        print("Cannot continue without an endpoint.")
        raise SystemExit(1)
    rcv.secret = ep["secret"]

    pid = pay(customer, merchant, mkey)
    act(pid, "authorize", mkey)
    act(pid, "capture", mkey)

    wanted = {"payment.created", "payment.authorized", "payment.captured"}
    ok = wait_for(lambda: set(rcv.accepted.values()) >= wanted, 60)
    check("2.  all 3 payment events delivered (despite receiver failing the first 2 tries)", ok,
          f"(received: {sorted(set(rcv.accepted.values()))})")
    check("3.  every request carried a valid HMAC signature", rcv.bad_signatures == 0 and len(rcv.hits) >= 3,
          f"(bad signatures: {rcv.bad_signatures})")
    check("4.  failed deliveries were retried (each event hit at least 3 times)",
          len(rcv.accepted) >= 3 and all(rcv.hits[e] >= 3 for e in rcv.accepted),
          f"(hits per event: {sorted(rcv.hits.values())})")

    ok = wait_for(lambda: len([e for e in events(mkey, 'DELIVERED')]) == 3, 15)
    evs = events(mkey, "DELIVERED")
    check("5.  events API shows 3 DELIVERED with attempt counts", ok and all(e["attempts"] >= 3 for e in evs),
          f"(attempts: {[e['attempts'] for e in evs]})")

    _, other_key = new_merchant("other-shop")
    check("6.  another merchant sees none of these events", events(other_key) == [])

    code, _ = call("POST", "/webhooks", {"url": "ftp://example.com/x"}, headers=mh(mkey))
    check("7.  invalid URL scheme -> 400", code == 400, f"(got {code})")

    code, _ = call("POST", f"/webhooks/events/{evs[0]['id']}/replay", headers=mh(mkey)) if evs else (0, None)
    check("8.  replaying a non-DEAD event -> 409", code == 409, f"(got {code})")

    # deactivate endpoint -> no new events
    before = len(events(mkey))
    code, _ = call("DELETE", f"/webhooks/{ep['id']}", headers=mh(mkey))
    pay(customer, merchant, mkey)
    time.sleep(1)
    check("9.  deactivated endpoint receives no new events", code == 204 and len(events(mkey)) == before,
          f"(delete: {code}, events {before} -> {len(events(mkey))})")

    # ===== merchant 2: endpoint nobody is listening on -> retries -> DEAD -> replay once it's up
    customer2 = new_account("bob", "CUSTOMER")
    merchant2, key2 = new_merchant("dlq-shop")
    code, ep2 = call("POST", "/webhooks", {"url": f"http://{APP_TO_HOST}:9999/hook"}, headers=mh(key2))
    pay(customer2, merchant2, key2)
    print("    ...waiting for retries to run out (about 15-30 s)...")
    ok = wait_for(lambda: len(events(key2, "DEAD")) == 1, 90)
    dead = events(key2, "DEAD")
    check("10. unreachable endpoint -> event ends DEAD (dead-letter) after max attempts",
          ok and dead[0]["attempts"] >= 5, f"(attempts: {dead[0]['attempts'] if dead else None}, "
                                           f"error: {dead[0]['last_error'] if dead else None})")

    if dead:
        rcv2 = Receiver(9999, fail_first=0)       # the merchant "fixes their server"
        rcv2.secret = ep2["secret"]
        code, _ = call("POST", f"/webhooks/events/{dead[0]['id']}/replay", headers=mh(key2))
        check("11. replay of a DEAD event -> 202", code == 202, f"(got {code})")
        ok = wait_for(lambda: dead[0]["id"] in rcv2.accepted, 30)
        check("12. replayed event is delivered once the endpoint is back", ok)
    else:
        check("11. replay of a DEAD event -> 202", False, "(no dead event to replay)")
        check("12. replayed event is delivered once the endpoint is back", False)

    rep = call("GET", "/admin/reconcile", headers=ADMIN)[1]
    check("13. books still balance", rep["healthy"] is True)

    print(f"\n{sum(results)}/{len(results)} checks passed")
    raise SystemExit(0 if all(results) else 1)


if __name__ == "__main__":
    main()
