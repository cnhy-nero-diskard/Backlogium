import { fail } from "./refresh-options.mjs";

export class RequestBudget {
  constructor(options, dependencies = {}, previous = {}) {
    this.options = options;
    this.fetch = dependencies.fetch ?? globalThis.fetch;
    this.now = dependencies.now ?? Date.now;
    this.sleep = dependencies.sleep ?? ((ms) => new Promise((r) => setTimeout(r, ms)));
    this.random = dependencies.random ?? Math.random;
    this.started = this.now(); this.previousElapsed = previous.elapsedMs ?? 0;
    this.lastStart = -Infinity; this.stopped = null;
    this.stats = { requests: previous.requests ?? 0, retries: previous.retries ?? 0,
      backoffMs: previous.backoffMs ?? 0, exhausted: false, elapsedMs: this.previousElapsed };
  }
  remaining() { return this.options.maxRunSeconds * 1000 - this.previousElapsed - (this.now() - this.started); }
  snapshot() { return { ...this.stats, elapsedMs: this.previousElapsed + this.now() - this.started }; }
  async wait(ms, backoff = false) {
    if (ms >= this.remaining()) { this.stats.exhausted = true; this.stopped = "budget-exhausted"; fail("budget-exhausted"); }
    if (backoff) this.stats.backoffMs += ms;
    if (ms > 0) await this.sleep(ms);
  }
  async request(url, kind) {
    for (let attempt = 0; attempt <= 2; attempt++) {
      if (this.stopped) fail(this.stopped);
      if (this.stats.requests >= this.options.maxRequests || this.remaining() <= 0) {
        this.stats.exhausted = true; this.stopped = "budget-exhausted"; fail("budget-exhausted");
      }
      await this.wait(Math.max(0, this.lastStart + 1000 - this.now()));
      const timeoutMs = Math.min(15000, this.remaining());
      this.lastStart = this.now(); this.stats.requests++;
      if (attempt) this.stats.retries++;
      let response, text, transport = false;
      const controller = new AbortController();
      let timer;
      try {
        [response, text] = await Promise.race([
          (async () => {
            const r = await this.fetch(url, { signal: controller.signal, redirect: "error", headers: {
              Accept: kind === "steam" ? "application/json" : "text/html",
              "User-Agent": "Backlogium-HLTB-Refresh/1",
            } });
            // Bound streaming response bodies as well as wall time.
            let body;
            if (r.body?.getReader) {
              const reader = r.body.getReader(); const chunks = []; let bytes = 0;
              try {
                while (true) {
                  const next = await reader.read(); if (next.done) break;
                  bytes += next.value.byteLength;
                  if (bytes > 5 * 1024 * 1024) { await reader.cancel(); fail("response-too-large"); }
                  chunks.push(Buffer.from(next.value));
                }
                body = Buffer.concat(chunks).toString("utf8");
              } finally { reader.releaseLock(); }
            } else body = await r.text();
            if (Buffer.byteLength(body) > 5 * 1024 * 1024) fail("response-too-large");
            return [r, body];
          })(),
          new Promise((_, reject) => { timer = setTimeout(() => { controller.abort(); reject(new Error("timeout")); }, timeoutMs); }),
        ]);
      } catch (error) {
        if (error.category === "response-too-large") throw error;
        transport = true;
      } finally { clearTimeout(timer); }
      if (!transport && response.status >= 200 && response.status < 300) return text;
      if (!transport && [401, 403].includes(response.status)) {
        if (kind === "hltb") this.stopped = "access-denied";
        fail("access-denied");
      }
      const retryable = transport || response.status === 429 || (response.status >= 500 && response.status <= 599);
      const category = transport ? "transport-failed" : response.status === 429 ? "rate-limited" : response.status >= 500 ? "server-failed" : "http-failed";
      if (!retryable || attempt === 2) fail(category);
      let delay = Math.min(30000, 1000 * 2 ** attempt + Math.floor(this.random() * 250));
      if (!transport && response.status === 429) {
        const raw = response.headers?.get("retry-after");
        if (raw && /^\d+(?:\.\d+)?$/.test(raw.trim())) delay = Math.max(delay, Number(raw) * 1000);
        else if (raw && Number.isFinite(Date.parse(raw))) delay = Math.max(delay, Date.parse(raw) - this.now());
      }
      await this.wait(delay, true);
    }
  }
}
