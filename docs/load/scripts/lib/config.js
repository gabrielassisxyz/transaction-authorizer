import { SharedArray } from 'k6/data';

// Everything a run needs to point at the SUT, taken from the environment so the scenario
// files never carry a machine-specific address. Defaults target a local stack; a real run
// overrides BASE_URL with the SUT address on the isolated pair.
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// The account-id sample produced by extract-accounts.sh after the seed drain. SharedArray
// parses it once and shares a single copy across every VU, instead of each VU holding its
// own copy of the ids. open() resolves relative to this file, which lives in lib/, so the
// default reaches up to accounts.json in the scripts directory where the extractor writes it.
const ACCOUNTS_FILE = __ENV.ACCOUNTS_FILE || '../accounts.json';

export const accounts = new SharedArray('accounts', function () {
  return JSON.parse(open(ACCOUNTS_FILE));
});

// The end-of-test summary omits p99 by default, exporting only p90 and p95. The results
// doc reports p99 per scenario, so every measured run asks for it explicitly, in one place
// so the three scenarios stay consistent.
export const TREND_STATS = ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max'];
