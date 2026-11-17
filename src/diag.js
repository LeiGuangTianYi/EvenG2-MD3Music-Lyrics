/**
 * Self-report channel.
 *
 * The plugin runs inside the Even app's WebView, where console output is invisible
 * to us. Everything that matters during boot (did the bridge appear, did the page
 * get created, why did a poll fail) is POSTed to the local lyrics bridge instead,
 * so it can be read back with `curl http://127.0.0.1:21771/v1/logs`.
 *
 * The request is deliberately a simple one — a plain text/plain body with no
 * custom headers — so it never triggers a CORS preflight.
 */
export const DEFAULT_LOG_URL = 'http://127.0.0.1:21771/v1/log';

export function createReporter({ url = DEFAULT_LOG_URL, limit = 200 } = {}) {
  let sent = 0;
  let failures = 0;

  return function report(message) {
    const text = typeof message === 'string' ? message : safeString(message);
    if (!text) return;
    // Keep the ring buffer on the phone from filling with the same poll error.
    if (sent >= limit) return;
    sent += 1;
    try {
      void fetch(url, { method: 'POST', body: text, cache: 'no-store' }).catch(() => {
        failures += 1;
      });
    } catch {
      failures += 1;
    }
  };
}

function safeString(value) {
  try {
    if (value instanceof Error) return `${value.name}: ${value.message}`;
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}
