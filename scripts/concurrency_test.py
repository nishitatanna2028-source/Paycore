#!/usr/bin/env python3
"""
Tries to break PayCore. Needs the server running on localhost:8080. No pip installs needed.
    python3 scripts/concurrency_test.py
"""
import json
import urllib.error
import urllib.request
import uuid
from collections import Counter
from concurrent.futures import ThreadPoolExecutor

BASE = "http://localhost:8080"
results = []


def call(method, path, body=None, key=None):
    data = json.dumps(body).encode() if body is not None else (b"" if method == "POST" else None)
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if key:
        req.add_header("Idempotency-Key", key)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"null")


def check(name, ok, detail=""):
    results.append(ok)
    print(f"[{'PASS' if ok else 'FAIL'}] {name} {detail}")


def new_account(name, type_):
    return call("POST", "/accounts", {"name": name, "type": type_})[1]["id"]


def deposit(acc, amount):
    call("POST", f"/accounts/{acc}/deposit", {"amount": amount}, key=str(uuid.uuid4()))


def balance(acc):
    return call("GET", f"/accounts/{acc}")[1]["balance"]


def pay(customer, merchant, amount):
    return call("POST", "/payments", {"customerId": customer, "merchantId": merchant, "amount": amount},
                key=str(uuid.uuid4()))[1]["id"]


# ---- Test 1: same Idempotency-Key sent 20 times in parallel -> exactly one payment ----
customer, merchant = new_account("alice", "CUSTOMER"), new_account("shop", "MERCHANT")
deposit(customer, 1000)
same_key = str(uuid.uuid4())
body = {"customerId": customer, "merchantId": merchant, "amount": 100}
with ThreadPoolExecutor(20) as ex:
    out = list(ex.map(lambda _: call("POST", "/payments", body, key=same_key), range(20)))
ids = {r[1].get("id") for r in out}
check("1. 20 parallel requests, same key -> one payment", len(ids) == 1 and all(r[0] == 201 for r in out),
      f"(distinct payment ids: {len(ids)})")

# ---- Test 2: same key, different body -> rejected ----
code, _ = call("POST", "/payments", {**body, "amount": 999}, key=same_key)
check("2. same key + different body -> 422", code == 422, f"(got {code})")

# ---- Test 3: overdraw attempt. Balance 100, 30 payments of 10, authorised in parallel ----
customer, merchant = new_account("bob", "CUSTOMER"), new_account("shop2", "MERCHANT")
deposit(customer, 100)
with ThreadPoolExecutor(30) as ex:
    pids = list(ex.map(lambda _: pay(customer, merchant, 10), range(30)))
with ThreadPoolExecutor(30) as ex:
    codes = Counter(r[0] for r in ex.map(
        lambda p: call("POST", f"/payments/{p}/authorize", key=str(uuid.uuid4())), pids))
check("3. exactly 10 of 30 authorisations succeed, balance never negative",
      codes[200] == 10 and balance(customer) == 0, f"(statuses: {dict(codes)}, balance: {balance(customer)})")

# ---- Test 4: capture race. 10 parallel captures of one payment -> money moves once ----
customer, merchant = new_account("carol", "CUSTOMER"), new_account("shop3", "MERCHANT")
deposit(customer, 500)
p = pay(customer, merchant, 200)
call("POST", f"/payments/{p}/authorize", key=str(uuid.uuid4()))
with ThreadPoolExecutor(10) as ex:
    codes = Counter(r[0] for r in ex.map(
        lambda _: call("POST", f"/payments/{p}/capture", key=str(uuid.uuid4())), range(10)))
check("4. 10 parallel captures -> 1 success, merchant paid once",
      codes[200] == 1 and balance(merchant) == 200, f"(statuses: {dict(codes)}, merchant: {balance(merchant)})")

# ---- Test 5: illegal state transitions ----
p2 = pay(customer, merchant, 50)
code, _ = call("POST", f"/payments/{p2}/refund", key=str(uuid.uuid4()))
check("5. refund before capture -> 409", code == 409, f"(got {code})")

# ---- Test 6: the books balance ----
rep = call("GET", "/admin/reconcile")[1]
check("6. reconciliation healthy", rep["healthy"] is True, f"({rep})")

print(f"\n{sum(results)}/{len(results)} checks passed")
raise SystemExit(0 if all(results) else 1)
