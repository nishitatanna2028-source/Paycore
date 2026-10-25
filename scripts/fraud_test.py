#!/usr/bin/env python3
"""
Fraud-rule checks. Needs PayCore running (docker compose up) with the fraud rules ON (the default).
    python scripts/fraud_test.py
Optional env: BASE_URL, ADMIN_KEY, FRAUD_VELOCITY_MAX (default 60), FRAUD_REVIEW_AMOUNT (default 10000000)
"""
import json
import os
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get("BASE_URL", "http://localhost:8080")
ADMIN = {"X-Admin-Key": os.environ.get("ADMIN_KEY", "dev-admin-key")}
VELOCITY_MAX = int(os.environ.get("FRAUD_VELOCITY_MAX", "60"))
REVIEW_AMOUNT = int(os.environ.get("FRAUD_REVIEW_AMOUNT", "10000000"))
results = []


def call(method, path, body=None, key=None, headers=None):
    data = json.dumps(body).encode() if body is not None else (b"" if method == "POST" else None)
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if key:
        req.add_header("Idempotency-Key", key)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            status, payload = r.status, r.read()
    except urllib.error.HTTPError as e:
        status, payload = e.code, e.read()
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
    return mid, call("POST", f"/admin/merchants/{mid}/api-keys", {"name": "t"}, headers=ADMIN)[1]["apiKey"]


def create(customer, merchant, mkey, amount):
    return call("POST", "/payments", {"customerId": customer, "merchantId": merchant, "amount": amount},
                key=str(uuid.uuid4()), headers=mh(mkey))


def main():
    customer = new_account("fraud-alice", "CUSTOMER")
    merchant, mkey = new_merchant("fraud-shop")
    call("POST", f"/accounts/{customer}/deposit", {"amount": REVIEW_AMOUNT * 3}, key=str(uuid.uuid4()), headers=ADMIN)

    # ---- large amount -> held for review
    code, small = create(customer, merchant, mkey, 100)
    check("1. normal payment has riskFlag NONE", code == 201 and small["riskFlag"] == "NONE", f"(got {code})")

    code, big = create(customer, merchant, mkey, REVIEW_AMOUNT)
    check("2. payment at the review threshold is created with riskFlag REVIEW",
          code == 201 and big["riskFlag"] == "REVIEW", f"(got {code}, flag {big and big.get('riskFlag')})")
    pid = big["id"]

    code, body = call("POST", f"/payments/{pid}/authorize", key=str(uuid.uuid4()), headers=mh(mkey))
    check("3. authorizing a payment under review is blocked -> 409", code == 409, f"(got {code}: {body and body.get('error')})")

    code, queue = call("GET", "/admin/review-queue", headers=ADMIN)
    check("4. payment appears in the admin review queue", code == 200 and any(p["id"] == pid for p in queue))

    code, _ = call("POST", f"/admin/payments/{pid}/approve", headers=mh(mkey))
    check("5. a merchant key cannot approve reviews -> 401", code == 401, f"(got {code})")

    code, approved = call("POST", f"/admin/payments/{pid}/approve", headers=ADMIN)
    check("6. admin approves -> riskFlag APPROVED", code == 200 and approved["riskFlag"] == "APPROVED", f"(got {code})")

    code, _ = call("POST", f"/payments/{pid}/authorize", key=str(uuid.uuid4()), headers=mh(mkey))
    check("7. approved payment can now be authorized -> 200", code == 200, f"(got {code})")

    code, _ = call("POST", f"/admin/payments/{pid}/approve", headers=ADMIN)
    check("8. approving twice -> 409", code == 409, f"(got {code})")

    # ---- velocity: a fresh customer makes VELOCITY_MAX + 5 payments in a row
    bot = new_account("fraud-bot", "CUSTOMER")
    codes = [create(bot, merchant, mkey, 100)[0] for _ in range(VELOCITY_MAX + 5)]
    ok_count, declined = codes.count(201), codes.count(422)
    check(f"9. velocity rule: first {VELOCITY_MAX} succeed, the next 5 are declined (422)",
          ok_count == VELOCITY_MAX and declined == 5, f"(created: {ok_count}, declined: {declined})")

    rep = call("GET", "/admin/reconcile", headers=ADMIN)[1]
    check("10. books still balance", rep["healthy"] is True)

    print(f"\n{sum(results)}/{len(results)} checks passed")
    raise SystemExit(0 if all(results) else 1)


if __name__ == "__main__":
    main()
