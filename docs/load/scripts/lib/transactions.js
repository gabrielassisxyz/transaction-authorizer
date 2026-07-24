import http from 'k6/http';
import { check } from 'k6';
import crypto from 'k6/crypto';
import { BASE_URL, accounts } from './config.js';

const HEX = [];
for (let i = 0; i < 256; i++) HEX.push((i + 0x100).toString(16).slice(1));

// A fresh v4 uuid per request. A reused transaction id is treated as an idempotent replay
// and answered from the stored decision, so reusing ids would measure the replay fast path
// instead of real authorization work. Built from k6/crypto random bytes so the harness has
// no run-time network import to resolve.
export function uuidv4() {
  const b = new Uint8Array(crypto.randomBytes(16));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  return (
    HEX[b[0]] + HEX[b[1]] + HEX[b[2]] + HEX[b[3]] + '-' +
    HEX[b[4]] + HEX[b[5]] + '-' +
    HEX[b[6]] + HEX[b[7]] + '-' +
    HEX[b[8]] + HEX[b[9]] + '-' +
    HEX[b[10]] + HEX[b[11]] + HEX[b[12]] + HEX[b[13]] + HEX[b[14]] + HEX[b[15]]
  );
}

// A seeded account drawn uniformly from the whole sample: the spread the steady, spike and
// warm-up runs use.
export function pickUniform() {
  return accounts[Math.floor(Math.random() * accounts.length)];
}

// A seeded account drawn from the first hotSize entries only: the concentrated spread the
// hot-account skew run uses to expose row-lock contention on the guarded update.
export function pickHot(hotSize) {
  const n = Math.min(hotSize, accounts.length);
  return accounts[Math.floor(Math.random() * n)];
}

// Amounts are whole reais so the request carries a clean two-decimal value and the mix stays
// readable. A fifty-fifty credit/debit split exercises both paths: credit and an affordable
// debit apply through the guarded update, while a debit past the balance takes the locked
// refusal path. Balances start at zero, so early debits refuse and later ones can apply as
// credits raise the same accounts.
function randomType() {
  return Math.random() < 0.5 ? 'CREDIT' : 'DEBIT';
}

function randomReais() {
  return 1 + Math.floor(Math.random() * 100);
}

// One authorization call against the given account, with a fresh id and a random typed
// amount. Both an approval and a refusal are HTTP 200 by contract, so the check treats only
// a non-200 as a failure: a refused debit is a real decision, not an error.
export function authorize(accountId) {
  const body = JSON.stringify({
    account_id: accountId,
    type: randomType(),
    amount: { value: randomReais(), currency: 'BRL' },
  });
  // The name tag collapses every request into one metric series. Without it k6 tags each
  // sample with the URL, and since the transaction id is new on every call that means one
  // time series per request: a three-minute run mints hundreds of thousands of them and
  // the generator dies to the OOM killer near the end, after the load has already been
  // applied and before any summary is written.
  const res = http.post(`${BASE_URL}/transactions/${uuidv4()}`, body, {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'POST /transactions/{transactionId}' },
  });
  check(res, {
    'status is 200': (r) => r.status === 200,
    'body carries a decision': (r) => {
      try {
        return typeof JSON.parse(r.body).transaction.status === 'string';
      } catch (e) {
        return false;
      }
    },
  });
  return res;
}
