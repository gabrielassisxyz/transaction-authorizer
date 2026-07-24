import { authorize, pickUniform } from './lib/transactions.js';

// Steady state: a fixed concurrency held long enough for throughput and latency to settle.
// This is the headline run, the one whose throughput and p50/p99 go in the results table.
// A short ramp lets the pool and JIT settle before the hold; only the hold is meant to be
// read. Under virtual threads the connection pool, not a thread count, is the concurrency
// ceiling, so raising VUS past the pool size is what surfaces the queue behind it.
export const options = {
  scenarios: {
    steady: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: Number(__ENV.VUS || 50) },
        { duration: __ENV.DURATION || '3m', target: Number(__ENV.VUS || 50) },
        { duration: '10s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  // A broken run fails loudly: if the app is down or the URL is wrong every request errors,
  // the error-rate threshold is crossed and k6 exits non-zero instead of printing tidy but
  // meaningless percentiles.
  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
};

export default function () {
  authorize(pickUniform());
}
