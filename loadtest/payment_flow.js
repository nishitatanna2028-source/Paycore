// PayCore load test: each virtual user runs "checkout" in a loop:
//   create payment -> authorize -> capture
// Closed-loop (constant VUs): measures the maximum sustainable throughput and the latency under it.
//
// Run (from the project folder):
//   docker compose --profile loadtest run --rm -e LABEL=baseline k6
// Options (pass with -e NAME=value): VUS (50), DURATION_S (60), MERCHANTS (50), LABEL (run)
import http from 'k6/http';
import { sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const ADMIN_KEY = __ENV.ADMIN_KEY || 'dev-admin-key';
const VUS = parseInt(__ENV.VUS || '50');
const DURATION_S = parseInt(__ENV.DURATION_S || '60');
const MERCHANTS = parseInt(__ENV.MERCHANTS || '50');
const LABEL = __ENV.LABEL || 'run';
const STEPS = ['create', 'authorize', 'capture'];

// only 200/201 count as success; anything else (429, 409, 500...) is an error
http.setResponseCallback(http.expectedStatuses(200, 201));

const appRequests = new Counter('app_requests');
const appErrors = new Rate('app_errors');

export const options = {
  scenarios: {
    checkout: { executor: 'constant-vus', vus: VUS, duration: DURATION_S + 's' },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  // These thresholds are lenient on purpose: they exist so k6 reports per-step latency sub-metrics.
  thresholds: {
    app_errors: ['rate<0.01'],
    'http_req_duration{name:create}': ['p(95)<5000'],
    'http_req_duration{name:authorize}': ['p(95)<5000'],
    'http_req_duration{name:capture}': ['p(95)<5000'],
  },
};

function adminPost(path, body, idemKey) {
  const headers = { 'Content-Type': 'application/json', 'X-Admin-Key': ADMIN_KEY };
  if (idemKey) headers['Idempotency-Key'] = idemKey;
  for (let attempt = 0; attempt < 30; attempt++) {
    const res = http.post(BASE + path, JSON.stringify(body), { headers: headers, tags: { name: 'setup' } });
    if (res.status === 429) {                       // admin is rate limited too: wait and retry
      sleep(parseFloat(res.headers['Retry-After'] || '1'));
      continue;
    }
    if (res.status >= 300) throw new Error('setup failed: ' + path + ' -> ' + res.status + ' ' + res.body);
    return res.json();
  }
  throw new Error('setup gave up after repeated 429s: ' + path);
}

// Runs once before the test: create merchants (with API keys) and funded customers.
export function setup() {
  const merchants = [];
  for (let i = 0; i < MERCHANTS; i++) {
    const m = adminPost('/accounts', { name: 'lt-merchant-' + i, type: 'MERCHANT' });
    const k = adminPost('/admin/merchants/' + m.id + '/api-keys', { name: 'loadtest' });
    merchants.push({ id: m.id, key: k.apiKey });
  }
  const customers = [];
  for (let i = 0; i < VUS; i++) {                   // one customer per VU: no customer-row contention
    const c = adminPost('/accounts', { name: 'lt-customer-' + i, type: 'CUSTOMER' });
    adminPost('/accounts/' + c.id + '/deposit', { amount: 1000000000 }, 'lt-dep-' + LABEL + '-' + Date.now() + '-' + i);
    customers.push(c.id);
  }
  return { merchants: merchants, customers: customers };
}

function post(path, body, headers, idemKey, step) {
  const h = Object.assign({ 'Idempotency-Key': idemKey }, headers);
  const res = http.post(BASE + path, body ? JSON.stringify(body) : '', { headers: h, tags: { name: step } });
  appRequests.add(1);
  return res;
}

function ok(res, status) {
  const good = res.status === status;
  appErrors.add(!good);
  return good;
}

export default function (data) {
  const merchant = data.merchants[(__VU - 1) % data.merchants.length];
  const customer = data.customers[(__VU - 1) % data.customers.length];
  const headers = { 'Content-Type': 'application/json', Authorization: 'Bearer ' + merchant.key };
  const id = LABEL + '-' + __VU + '-' + __ITER + '-' + Date.now();

  const created = post('/payments', { customerId: customer, merchantId: merchant.id, amount: 100 },
    headers, 'c-' + id, 'create');
  if (!ok(created, 201)) return;
  const paymentId = created.json('id');

  const authorized = post('/payments/' + paymentId + '/authorize', null, headers, 'a-' + id, 'authorize');
  if (!ok(authorized, 200)) return;

  const captured = post('/payments/' + paymentId + '/capture', null, headers, 'k-' + id, 'capture');
  ok(captured, 200);
}

function stepStats(data, step) {
  const m = data.metrics['http_req_duration{name:' + step + '}'];
  if (!m) return null;
  const v = m.values;
  return { avg: v.avg, p50: v.med, p90: v['p(90)'], p95: v['p(95)'], p99: v['p(99)'], max: v.max };
}

export function handleSummary(data) {
  const requests = data.metrics.app_requests ? data.metrics.app_requests.values.count : 0;
  const errorRate = data.metrics.app_errors ? data.metrics.app_errors.values.rate : 0;
  const summary = {
    label: LABEL,
    vus: VUS,
    durationS: DURATION_S,
    merchants: MERCHANTS,
    requests: requests,
    throughputRps: requests / DURATION_S,
    errorRatePct: errorRate * 100,
    steps: {},
  };
  STEPS.forEach(function (s) { summary.steps[s] = stepStats(data, s); });

  const f = function (n) { return n === undefined || n === null ? '-' : n.toFixed(1); };
  let text = '\n=== PayCore load test: ' + LABEL + ' ===\n' +
    'VUs: ' + VUS + '   duration: ' + DURATION_S + 's   merchants: ' + MERCHANTS + '\n' +
    'requests: ' + requests + '   throughput: ' + summary.throughputRps.toFixed(1) + ' req/s' +
    '   errors: ' + summary.errorRatePct.toFixed(2) + '%\n\n' +
    '| step      |   avg |   p50 |   p95 |   p99 |   max |   (ms)\n' +
    '|-----------|-------|-------|-------|-------|-------|\n';
  STEPS.forEach(function (s) {
    const x = summary.steps[s];
    if (x) {
      text += '| ' + s.padEnd(9) + ' | ' + f(x.avg).padStart(5) + ' | ' + f(x.p50).padStart(5) + ' | ' +
        f(x.p95).padStart(5) + ' | ' + f(x.p99).padStart(5) + ' | ' + f(x.max).padStart(5) + ' |\n';
    }
  });
  if (summary.errorRatePct > 5) {
    text += '\n*** WARNING: ' + summary.errorRatePct.toFixed(1) + '% of requests failed, so these numbers are NOT valid. ***\n' +
      'Most likely cause: per-key rate limiting (HTTP 429). See README > Performance, step 0.\n';
  }
  text += '\n(saved to loadtest/results/' + LABEL + '.json)\n';

  const out = { stdout: text };
  out['/loadtest/results/' + LABEL + '.json'] = JSON.stringify(summary, null, 2);
  return out;
}
