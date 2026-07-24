import { authorize, pickUniform } from './lib/transactions.js';
import { TREND_STATS } from './lib/config.js';

// Spike: a calm baseline, then a sudden jump to a burst well above it, then back down. It
// shows how the service absorbs a surge, whether latency recovers once the burst passes,
// and whether errors appear only at the peak or linger after it. Its percentiles are read
// apart from the steady-state ones, since a spike deliberately mixes two regimes.
export const options = {
  summaryTrendStats: TREND_STATS,
  scenarios: {
    spike: {
      executor: 'ramping-vus',
      startVUs: Number(__ENV.BASE_VUS || 20),
      stages: [
        { duration: '30s', target: Number(__ENV.BASE_VUS || 20) },
        { duration: '10s', target: Number(__ENV.SPIKE_VUS || 200) },
        { duration: '30s', target: Number(__ENV.SPIKE_VUS || 200) },
        { duration: '10s', target: Number(__ENV.BASE_VUS || 20) },
        { duration: '30s', target: Number(__ENV.BASE_VUS || 20) },
      ],
      gracefulRampDown: '10s',
    },
  },
  // Relaxed against steady state: at the peak the pool can saturate and shed some requests,
  // which is behavior to observe, not a broken run. A hard break (app down, wrong URL) still
  // pushes the rate far past this and fails loudly.
  thresholds: {
    http_req_failed: ['rate<0.05'],
    checks: ['rate>0.95'],
  },
};

export default function () {
  authorize(pickUniform());
}
