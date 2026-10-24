#!/usr/bin/env python3
"""
Tries to break PayCore. Needs the server running on localhost:8080. No pip installs needed.
    python scripts/concurrency_test.py
Optional env vars: BASE_URL, ADMIN_KEY (default dev-admin-key)
"""
import json
import os
import urllib.error
import urllib.request
import uuid
from collections import Counter
from concurrent.futures import ThreadPoolExecutor

BASE = os.environ.get("BASE_URL", "http://localhost:8080")
ADMIN = {"X-Admin-Key": os.environ.get("ADMIN_KEY", "dev-admin-key")}
results = []


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
            return r.status, r.read(), {k.lower(): v for k, v in r.headers.items()}
    except urllib.error.HTTPError as e:
        return e.code, e.read(), {k.lower(): v for k, v in e.headers.items()}


def call(method, path, body=None, key=None, headers=None):
    status, payload, _ = raw(method, path, body, key, headers)
    try:
        parsed = json.loads(payload) if payload else None
    except ValueError:
        parsed = None
    return status, parsed


def check(name, ok, detail=""):
    results.append(ok)
    print(f"[{'PASS' if ok else 'FAIL'}] {name} {detail}")


def mh(api_key):
    return {"Authorization": f"Bearer {api_key}"}


def new_account(name, type_):
    return call("POST", "/accounts", {"name": name, "type": type_}, headers=ADMIN)[1]["id"]


def new_merchant(name):
    """Returns (merchant_id, api_key, api_key_id)."""
    mid = new_account(name, "MERCHANT")
    _, body = call("POST", f"/admin/merchants/{mid}/api-keys", {"name": "test"}, headers=ADMIN)
    return mid, body["apiKey"], body["id"]


def deposit(acc, amount):
    call("POST", f"/accounts/{acc}/deposit", {"amount": amount}, key=str(uuid.uuid4()), headers=ADMIN)


def balance(acc):
    return call("GET", f"/accounts/{acc}", headers=ADMIN)[1]["balance"]


def pay(customer, merchant, mkey, amount):
    return call("POST", "/payments", {"customerId": customer, "merchantId": merchant, "amount": amount},
                key=str(uuid.uuid4()), headers=mh(mkey))[1]["id"]


def act(pid, action, mkey):
    return call("POST", f"/payments/{pid}/{action}", key=str(uuid.uuid4()), headers=mh(mkey))


# ---- 1: same Idempotency-Key sent 20 times in parallel -> exactly one payment ----
customer = new_account("alice", "CUSTOMER")
merchant, mkey, _ = new_merchant("shop")
deposit(customer, 1000)
same_key = str(uuid.uuid4())
body = {"customerId": customer, "merchantId": merchant, "amount": 100}
with ThreadPoolExecutor(20) as ex:
    out = list(ex.map(lambda _: call("POST", "/payments", body, key=same_key, headers=mh(mkey)), range(20)))
ids = {r[1].get("id") for r in out}
check("1.  20 parallel requests, same key -> one payment",
      len(ids) == 1 and all(r[0] == 201 for r in out), f"(distinct payment ids: {len(ids)})")

# ---- 2: same key, different body -> rejected ----
code, _ = call("POST", "/payments", {**body, "amount": 999}, key=same_key, headers=mh(mkey))
check("2.  same key + different body -> 422", code == 422, f"(got {code})")

# ---- 3: overdraw attempt. Balance 100, 30 payments of 10, authorised in parallel ----
customer3 = new_account("bob", "CUSTOMER")
merchant3, mkey3, _ = new_merchant("shop2")
deposit(customer3, 100)
with ThreadPoolExecutor(30) as ex:
    pids = list(ex.map(lambda _: pay(customer3, merchant3, mkey3, 10), range(30)))
with ThreadPoolExecutor(30) as ex:
    codes = Counter(r[0] for r in ex.map(lambda p: act(p, "authorize", mkey3), pids))
check("3.  exactly 10 of 30 authorisations succeed, balance never negative",
      codes[200] == 10 and balance(customer3) == 0,
      f"(statuses: {dict(codes)}, balance: {balance(customer3)})")

# ---- 4: capture race. 10 parallel captures of one payment -> money moves once ----
customer4 = new_account("carol", "CUSTOMER")
merchant4, mkey4, _ = new_merchant("shop3")
deposit(customer4, 500)
p = pay(customer4, merchant4, mkey4, 200)
act(p, "authorize", mkey4)
with ThreadPoolExecutor(10) as ex:
    codes = Counter(r[0] for r in ex.map(lambda _: act(p, "capture", mkey4), range(10)))
check("4.  10 parallel captures -> 1 success, merchant paid once",
      codes[200] == 1 and balance(merchant4) == 200, f"(statuses: {dict(codes)}, merchant: {balance(merchant4)})")

# ---- 5: illegal state transition ----
p2 = pay(customer4, merchant4, mkey4, 50)
code, _ = act(p2, "refund", mkey4)
check("5.  refund before capture -> 409", code == 409, f"(got {code})")

# ---- 6: authentication ----
code, _ = call("POST", "/payments", body, key=str(uuid.uuid4()))
check("6a. no API key -> 401", code == 401, f"(got {code})")
code, _ = call("POST", "/payments", body, key=str(uuid.uuid4()), headers=mh("pk_not_a_real_key"))
check("6b. fake API key -> 401", code == 401, f"(got {code})")
code, _ = call("GET", "/admin/reconcile", headers=mh(mkey4))
check("6c. merchant key cannot use admin endpoints -> 401", code == 401, f"(got {code})")
code, _ = call("POST", "/accounts", {"name": "x", "type": "CUSTOMER"})
check("6d. creating accounts without admin key -> 401", code == 401, f"(got {code})")

# ---- 7: tenant isolation: merchant A (shop3) vs merchant B (shop) ----
code, _ = call("GET", f"/payments/{p2}", headers=mh(mkey))
check("7a. merchant B cannot read merchant A's payment -> 404", code == 404, f"(got {code})")
code, _ = act(p2, "authorize", mkey)
check("7b. merchant B cannot authorize merchant A's payment -> 404", code == 404, f"(got {code})")
code, _ = call("POST", "/payments", {"customerId": customer4, "merchantId": merchant4, "amount": 5},
               key=str(uuid.uuid4()), headers=mh(mkey))
check("7c. merchant B cannot create payments for merchant A -> 403", code == 403, f"(got {code})")

# ---- 8: idempotency keys are scoped per merchant ----
shared = "shared-key-" + str(uuid.uuid4())[:8]
ca, a = call("POST", "/payments", {"customerId": customer4, "merchantId": merchant4, "amount": 7},
             key=shared, headers=mh(mkey4))
cb, b = call("POST", "/payments", {"customerId": customer4, "merchantId": merchant, "amount": 7},
             key=shared, headers=mh(mkey))
check("8.  same Idempotency-Key from two merchants -> two separate payments",
      ca == 201 and cb == 201 and a["id"] != b["id"], f"(statuses: {ca}, {cb})")

# ---- 9: revoked key stops working ----
merchant9, mkey9, kid9 = new_merchant("shop-revoked")
code_before, _ = call("GET", f"/payments/{uuid.uuid4()}", headers=mh(mkey9))
code_del, _ = call("DELETE", f"/admin/api-keys/{kid9}", headers=ADMIN)
code_after, _ = call("GET", f"/payments/{uuid.uuid4()}", headers=mh(mkey9))
check("9.  revoked key -> 401", code_before == 404 and code_del == 204 and code_after == 401,
      f"(before: {code_before}, revoke: {code_del}, after: {code_after})")

# ---- 10: rate limiting: 1000 rapid requests on a fresh key ----
merchant10, mkey10, _ = new_merchant("shop-ratelimit")


def hit(_):
    code, _, hdrs = raw("GET", f"/payments/{uuid.uuid4()}", headers=mh(mkey10))
    return code, hdrs.get("retry-after")


with ThreadPoolExecutor(50) as ex:
    out = list(ex.map(hit, range(1000)))
codes = Counter(c for c, _ in out)
check("10. burst of 1000 requests is throttled (some 429 with Retry-After)",
      codes[429] > 0 and codes[404] > 0 and all(r for c, r in out if c == 429),
      f"(statuses: {dict(codes)})")

# ---- 11: the books balance ----
rep = call("GET", "/admin/reconcile", headers=ADMIN)[1]
check("11. reconciliation healthy", rep["healthy"] is True, f"({rep})")

print(f"\n{sum(results)}/{len(results)} checks passed")
raise SystemExit(0 if all(results) else 1)
