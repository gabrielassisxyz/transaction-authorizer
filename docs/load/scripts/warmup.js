import { authorize, pickUniform } from './lib/transactions.js';

// Warm-up: drive a light, steady load so the JVM reaches its JIT-compiled state and the
// connection pool fills before the measured runs. Its numbers are discarded on purpose, so
// it carries no pass/fail thresholds; it exists only to leave the SUT warm.
export const options = {
  scenarios: {
    warmup: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 10),
      duration: __ENV.DURATION || '60s',
    },
  },
};

export default function () {
  authorize(pickUniform());
}
