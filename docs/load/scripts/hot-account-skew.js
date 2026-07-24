import { authorize, pickHot } from './lib/transactions.js';
import { TREND_STATS } from './lib/config.js';

// Hot-account skew: the same steady concurrency, but traffic concentrated on a handful of
// accounts instead of spread across the sample. Every request to a hot account contends for
// the same row, so this is where the guarded update and the FOR UPDATE refusal path serialize
// on the row lock. It exists to show that contention honestly, in numbers, rather than hide
// it behind uniform load that almost never touches the same row twice.
export const options = {
  summaryTrendStats: TREND_STATS,
  scenarios: {
    hot_account_skew: {
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
  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
};

const HOT_SIZE = Number(__ENV.HOT_ACCOUNTS || 10);

export default function () {
  authorize(pickHot(HOT_SIZE));
}
