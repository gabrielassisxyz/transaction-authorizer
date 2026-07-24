import { authorize, pickUniform } from './lib/transactions.js';

// Warm-up: drive a light, steady load so the JVM reaches its JIT-compiled state and the
// connection pool fills before the measured runs. Its numbers are discarded on purpose, but
// its success is not: a warm-up against a dead app or a wrong URL warms nothing, and without
// a threshold it would exit 0 and hand a cold SUT to the run that gets reported.
export const options = {
  scenarios: {
    warmup: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 10),
      duration: __ENV.DURATION || '60s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
};

export default function () {
  authorize(pickUniform());
}
