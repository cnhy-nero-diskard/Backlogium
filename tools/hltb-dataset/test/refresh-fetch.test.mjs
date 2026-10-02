import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { parseOptions } from "../refresh-options.mjs";
import { parseLibrary, selectTargets } from "../refresh-targets.mjs";
import { parseGamePage } from "../refresh-parser.mjs";
import { RequestBudget } from "../refresh-network.mjs";

const options = (tail = []) => parseOptions(["--steam-id", "76561198000000001", ...tail]);
const base = { schemaVersion: 1, datasetVersion: 2, gatheredAt: 1000,
  mappings: [[10, 100], [20, 100], [30, 200]], lengths: [[100, 100, 200, 300, null]] };
const scope = { libraries: { requested: 1, readable: 1, unavailable: 0, empty: 0 }, apps: [10, 20, 30, 40].map((appId) => ({ appId, title: null })) };
const page = (id, lengths, extra = {}) => `<script type="application/json" id="__NEXT_DATA__">${JSON.stringify({ props: { pageProps: { game: {
  game_id: id, game_name: "A public game", ...Object.fromEntries(["comp_main", "comp_plus", "comp_100", "comp_all"].map((f, i) => [f, lengths[i]])), ...extra,
} } } })}</script>`;

test("library parsing distinguishes readable-empty, unavailable, malformed, and personal metadata", () => {
  assert.deepEqual(parseLibrary('{"response":{"game_count":0}}'), []);
  for (const text of ["oops", "{}", '{"response":{}}', '{"response":{"game_count":2,"games":[]}}',
    '{"response":{"game_count":1,"games":[{"appid":"10"}]}}', '{"response":{"game_count":0,"games":[{}]}}']) assert.equal(parseLibrary(text), null);
  const games = parseLibrary(JSON.stringify({ accountName: "PRIVATE", response: { game_count: 1, games: [{ appid: 10, name: "Public Title", owner: "PRIVATE", profile: "PRIVATE" }] } }));
  assert.deepEqual(games, [{ appId: 10, title: "Public Title" }]);
});
test("selection deduplicates shared IDs, handles modes and rejects all correspondence conflicts", () => {
  let plan = selectTargets(base, null, scope, options(), {}, 10000);
  assert.deepEqual(plan.selected, [200]); assert.equal(plan.uniqueHltbIds, 2); assert.equal(plan.unmapped.length, 1);
  assert.deepEqual(selectTargets(base, null, scope, options(["--mode", "refresh-all"]), {}, 10000).selected, [100, 200]);
  assert.deepEqual(selectTargets(base, null, scope, options(["--mode", "stale", "--max-age-days", "1"]), {}, 10000).selected, [100, 200]);
  const reviewed = { ...base, mappings: [[40, 300], [50, 400], [10, 999]], lengths: [] };
  plan = selectTargets(base, reviewed, scope, options(), {}, 10000);
  assert.deepEqual(plan.addedMappings, [[40, 300]]); assert.deepEqual(plan.selected, [200, 300]);
  assert.deepEqual(plan.conflicts, [{ appId: 10, existingHltbId: 100, reviewedHltbId: 999 }]);
  assert.equal(plan.mappings.find(([a]) => a === 10)[1], 100);
});
test("structured Android page examples validate seconds, partial lengths, identity and failure bounds", async () => {
  const fixture = (name) => readFile(new URL(`../../../app/src/test/resources/com/example/backlogium/data/hltb/page/${name}.html`, import.meta.url), "utf8");
  assert.deepEqual(parseGamePage(await fixture("hltb-game-page-sample"), 7231).lengths, [512, 824, 1353, 1083]);
  assert.deepEqual(parseGamePage(await fixture("hltb-game-page-partial"), 9999).lengths, [120, null, null, null]);
  for (const name of ["hltb-game-page-not-found", "hltb-game-page-legacy"]) {
    const html = await fixture(name); assert.throws(() => parseGamePage(html, 1));
  }
  assert.throws(() => parseGamePage("captcha", 1), /challenge-response/);
  assert.throws(() => parseGamePage(page(100, [60, 0, null, 0]), 101), /identity-mismatch/);
  for (const v of [-1, "60", 0.5, 36000001]) assert.throws(() => parseGamePage(page(100, [v, 0, 0, 0]), 100), /invalid-length/);
  assert.deepEqual(parseGamePage(page(100, [29, 30, 36000000, 0]), 100).lengths, [0, 1, 600000, null]);
  assert.throws(() => parseGamePage('<script id="__NEXT_DATA__" type="application/json">bad</script>', 1), /malformed-page/);
  assert.equal(parseGamePage(page(100, [60, 0, 0, 0], { game_name: "https://steamcommunity.com/PRIVATE" }), 100).title, null);
});
function harness(sequence, tail = []) {
  let time = 10000; const starts = []; let active = 0, maxActive = 0;
  const budget = new RequestBudget(options(tail), {
    now: () => time, sleep: async (ms) => { time += ms; }, random: () => 0,
    fetch: async () => { starts.push(time); active++; maxActive = Math.max(maxActive, active);
      const item = sequence.shift(); active--; if (item instanceof Error) throw item;
      return new Response(item.body ?? "OK", { status: item.status ?? 200, headers: item.headers });
    },
  });
  return { budget, starts, maxActive: () => maxActive };
}
test("transient/transport recovery, Retry-After and serial request spacing", async () => {
  const h = harness([{ status: 429, headers: { "Retry-After": "3" } }, { status: 503 }, { body: "GOOD" }, new Error("PRIVATE"), { body: "RECOVERED" }]);
  assert.equal(await h.budget.request("https://example.test", "hltb"), "GOOD");
  assert.equal(await h.budget.request("https://example.test", "hltb"), "RECOVERED");
  assert.equal(h.maxActive(), 1); assert.equal(h.budget.stats.retries, 3);
  assert.equal(h.budget.stats.backoffMs, 6000);
  assert.ok(h.starts.every((s, i) => i === 0 || s - h.starts[i - 1] >= 1000));
});
test("access denial, finite retries and attempt/runtime limits terminate requests", async () => {
  let h = harness([{ status: 403 }]); await assert.rejects(h.budget.request("x", "hltb"), /access-denied/);
  await assert.rejects(h.budget.request("x", "hltb"), /access-denied/); assert.equal(h.budget.stats.requests, 1);
  h = harness([{ status: 500 }, { status: 500 }, { status: 500 }]); await assert.rejects(h.budget.request("x", "hltb"), /server-failed/); assert.equal(h.budget.stats.requests, 3);
  h = harness([{ status: 429, headers: { "Retry-After": "60" } }], ["--max-run-seconds", "2"]);
  await assert.rejects(h.budget.request("x", "hltb"), /budget-exhausted/); assert.equal(h.budget.stats.exhausted, true);
  h = harness([{}], ["--max-requests", "1"]); await h.budget.request("x", "steam");
  await assert.rejects(h.budget.request("x", "hltb"), /budget-exhausted/); assert.equal(h.budget.stats.requests, 1);
});
test("request timeout covers fetch and stalled response body within the runtime budget", async () => {
  for (const fetch of [async () => new Promise(() => {}), async () => ({ status: 200, text: async () => new Promise(() => {}) })]) {
    const budget = new RequestBudget(options(["--max-run-seconds", "1"]), { fetch });
    await assert.rejects(budget.request("https://example.test", "hltb"), /budget-exhausted/);
    assert.equal(budget.stats.requests, 1);
  }
});
