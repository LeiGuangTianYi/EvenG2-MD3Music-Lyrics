export const DEFAULT_BRIDGE_URL = 'http://127.0.0.1:21771/v1/lyrics';
export const FALLBACK_BRIDGE_URL = 'http://localhost:21771/v1/lyrics';

function snippet(text) {
  const clean = String(text ?? '').replace(/\s+/g, ' ').trim();
  return clean.length > 160 ? `${clean.slice(0, 160)}…` : clean;
}

/**
 * Reads the lyric snapshot that the LSPosed module exposes on the phone loopback.
 * A short timeout matters: the bridge lives in the MD3Music process and is simply
 * absent while that app is not running.
 *
 * Both loopback spellings are tried: the app resolves them identically, but a
 * WebView that filters URLs by whitelist may only accept the one written in
 * app.json — trying both removes that as a failure mode.
 */
export function createBridgeClient({
  url,
  urls = url ? [url] : [DEFAULT_BRIDGE_URL, FALLBACK_BRIDGE_URL],
  timeoutMs = 1500,
  fetchImpl = globalThis.fetch,
} = {}) {
  let lastGood = 0;

  async function readOne(url) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const response = await fetchImpl(url, {
        cache: 'no-store',
        signal: controller.signal,
        headers: { accept: 'application/json' },
      });
      const body = await response.text();
      if (!response.ok) {
        throw new Error(`HTTP ${response.status} @${url} body=${snippet(body)}`);
      }
      try {
        return JSON.parse(body);
      } catch {
        // An HTML body here means something other than the bridge answered — the
        // Even app's WebView proxy, a permission interstitial, or a stale server.
        throw new Error(`非 JSON 响应 @${url} ${response.status} body=${snippet(body)}`);
      }
    } finally {
      clearTimeout(timer);
    }
  }

  return async function readLyrics() {
    const order = [lastGood, ...urls.filter((_, i) => i !== lastGood)];
    let lastError;
    for (const index of order) {
      try {
        const payload = await readOne(urls[index]);
        lastGood = index;
        return payload;
      } catch (error) {
        lastError = error;
      }
    }
    throw lastError ?? new Error('no bridge url configured');
  };
}
