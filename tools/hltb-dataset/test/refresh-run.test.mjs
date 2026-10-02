import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, readdir, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { parseOptions } from "../refresh-options.mjs";
import { runRefresh, createCandidate } from "../refresh-run.mjs";
import { serializeDataset } from "../merge.mjs";
import { hash, readJson, fingerprint, writeJson } from "../refresh-storage.mjs";
import { identityFor, selectTargets } from "../refresh-targets.mjs";
import { main } from "../refresh.mjs";

const ids = ["76561198000000001", "76561198000000002"];
const base = { schemaVersion: 1, datasetVersion: 2, gatheredAt: 1700000000000,
  mappings: [[10, 100], [20, 100], [30, 200]], lengths: [[100, 100, 200, 300, null], [200, 100, null, null, null]] };
function page(id, lengths = [6000, 12000, 18000, 0], extra = {}) {
  return `<script id="__NEXT_DATA__" type="application/json">${JSON.stringify({ props: { pageProps: { game: {
    game_id: id, game_name: "Public Game", comp_main: lengths[0], comp_plus: lengths[1], comp_100: lengths[2], comp_all: lengths[3], ...extra,
  } } } })}</script>`;
}
async function fixture(t, tail = [], data = base) {
  const dir = await mkdtemp(path.join(os.tmpdir(), "hltb-refresh-"));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const source = path.join(dir, "source.json"); const text = serializeDataset(data); await writeFile(source, text);
  const options = parseOptions(["--steam-id", ids[0], "--state-dir", path.join(dir, "state"), "--mode", "refresh-all", ...tail]);
  let time = 1700000100000; const requests = [];
  const dependencies = { source, env: { STEAM_WEB_API_KEY: "SYNTHETIC_CREDENTIAL" }, now: () => time,
    sleep: async (ms) => { time += ms; }, random: () => 0,
    fetch: async (url) => {
      requests.push(url);
      if (url.includes("steampowered")) return new Response(JSON.stringify({ response: { game_count: 2, games: [{ appid: 10, name: "Game A" }, { appid: 30, name: "Game B" }] } }));
      const id = Number(url.split("/").at(-1)); return new Response(page(id));
    } };
  return { dir, source, text, options, dependencies, requests };
}
test("union/intersection, mixed unavailable libraries, shared-entry aliases and all four styles", async (t) => {
  const f = await fixture(t, ["--steam-id", ids[1], "--app-id", "10", "--app-id", "30", "--app-id", "999"]);
  const original = f.dependencies.fetch;
  f.dependencies.fetch = async (url, opts) => url.includes(ids[1]) ? (f.requests.push(url), new Response('{"response":{}}')) : original(url, opts);
  f.dependencies.fetch = ((prior) => async (url, opts) => url.endsWith("/100") ? (f.requests.push(url), new Response(page(100, [6030, 0, 18000, 0]))) : prior(url, opts))(f.dependencies.fetch);
  const result = await runRefresh(f.options, f.dependencies);
  assert.equal(result.exitCode, 2); assert.equal(result.report.outcome, "partial");
  assert.deepEqual(result.report.libraries, { requested: 2, readable: 1, unavailable: 1, empty: 0 });
  assert.equal(result.report.scope.targetedApps, 2); assert.equal(result.report.scope.selectedHltbIds, 2);
  assert.equal(f.requests.filter((u) => u.endsWith("/100")).length, 1);
  assert.deepEqual(result.candidate.lengths.find(([id]) => id === 100), [100, 101, null, 300, null]);
  assert.equal(result.report.affectedApps.length, 3);
  assert.equal(result.report.affectedApps.find((a) => a.appId === 20).directlyTargeted, false);
  assert.equal(result.candidate.datasetVersion, base.datasetVersion + 1);
  assert.equal(result.report.candidate.sha256, hash(await readFile(f.options.output)));
  assert.equal(await readFile(f.source, "utf8"), f.text);
});
test("all readable empty libraries complete; no readable scope blocks without fallback", async (t) => {
  const f = await fixture(t);
  for (const body of ['{"response":{"game_count":0}}', '{"response":{}}']) {
    f.dependencies.fetch = async (url) => { f.requests.push(url); return new Response(body); };
    const r = await runRefresh(f.options, f.dependencies);
    assert.equal(r.report.outcome, body.includes("game_count") ? "complete" : "blocked");
    assert.equal(r.report.scope.targetedApps, 0); assert.equal(r.report.scope.selectedHltbIds, 0);
  }
  assert.ok(f.requests.every((u) => u.includes("steampowered")));
});
test("reviewed mappings are scope-only and their lengths are never fresh evidence", async (t) => {
  const f = await fixture(t);
  const reviewed = path.join(f.dir, "reviewed.json");
  await writeFile(reviewed, serializeDataset({ ...base, datasetVersion: 0, mappings: [[40, 300], [50, 400]], lengths: [[300, 999, null, null, null]] }));
  f.options.reviewedMappings = reviewed;
  f.dependencies.fetch = async (url) => {
    f.requests.push(url);
    return new Response(url.includes("steampowered") ? '{"response":{"game_count":2,"games":[{"appid":40},{"appid":60}]}}' : page(300, [0, 0, 0, 0]));
  };
  let r = await runRefresh(f.options, f.dependencies);
  assert.equal(r.report.outcome, "partial"); assert.deepEqual(r.report.addedMappings, [[40, 300]]);
  assert.ok(!r.candidate.mappings.some(([a]) => a === 50)); assert.ok(!r.candidate.lengths.some(([id]) => id === 300));
  assert.deepEqual(r.report.noLengths, [300]); assert.deepEqual(r.report.unmapped, [{ appId: 60, title: null }]);
  await writeFile(reviewed, serializeDataset({ ...base, mappings: [[10, 999]], lengths: [] }));
  f.options.output = path.join(f.dir, "blocked-candidate.json");
  r = await runRefresh(f.options, f.dependencies);
  assert.equal(r.exitCode, 1); assert.equal(r.report.mappingConflicts.length, 1);
  await assert.rejects(readFile(f.options.output), { code: "ENOENT" });
});
test("usable unchanged observations advance evidence; no-lengths/failures retain values and history", async (t) => {
  const f = await fixture(t);
  f.dependencies.fetch = ((prior) => async (url, opts) => url.endsWith("/200") ? (f.requests.push(url), new Response(page(200, [6000, 0, 0, 0]))) : prior(url, opts))(f.dependencies.fetch);
  let r = await runRefresh(f.options, f.dependencies);
  assert.equal(r.exitCode, 0); assert.equal(r.report.changed, false);
  assert.equal(await readFile(f.options.output, "utf8"), f.text); assert.equal(r.report.checked.unchangedEntries, 2);
  const evidence = await readFile(path.join(f.options.stateDir, "history.json"), "utf8");
  assert.ok(JSON.parse(evidence).entries[100].gatheredAt > base.gatheredAt);
  f.dependencies.fetch = ((prior) => async (url, opts) => {
    if (url.endsWith("/100")) return new Response(page(100, [0, 0, 0, 0]));
    if (url.endsWith("/200")) return new Response("private denied", { status: 403 });
    return prior(url, opts);
  })(f.dependencies.fetch);
  r = await runRefresh(f.options, f.dependencies);
  assert.equal(r.report.outcome, "partial"); assert.deepEqual(r.report.noLengths, [100]);
  assert.deepEqual(r.candidate, base); assert.equal(await readFile(path.join(f.options.stateDir, "history.json"), "utf8"), evidence);
});
test("stale history binds mapping identity and canonical values, not global timestamp", () => {
  const options = parseOptions(["--steam-id", ids[0], "--mode", "stale", "--max-age-days", "1"]);
  const scope = { libraries: { requested: 1, readable: 1, unavailable: 0, empty: 0 }, apps: [{ appId: 10 }] };
  const history = { historyVersion: 1, entries: { 100: { gatheredAt: 10000, identity: identityFor(100, base.mappings), canonicalFingerprint: fingerprint(base.lengths[0]), observedFingerprint: fingerprint(base.lengths[0]) } } };
  assert.deepEqual(selectTargets(base, null, scope, options, history, 20000).selected, []);
  for (const mutate of [(h) => h.entries[100].identity = "changed", (h) => h.entries[100].canonicalFingerprint = "changed", (h) => h.historyVersion = 9, (h) => h.entries[100].gatheredAt = 1]) {
    const copy = structuredClone(history); mutate(copy);
    assert.deepEqual(selectTargets(base, null, scope, options, copy, 86400001).selected, [100]);
  }
});
test("checkpoint interruption resumes remaining work without Steam reads or retimestamping", async (t) => {
  const f = await fixture(t); let saved;
  f.dependencies.afterCheckpoint = async (state) => { saved = structuredClone(state); throw new Error("interrupted"); };
  await assert.rejects(runRefresh(f.options, f.dependencies), /interrupted/);
  const observation = structuredClone(saved.observations[100]);
  f.dependencies.afterCheckpoint = undefined; f.options.resume = saved.runId; f.requests.length = 0;
  const r = await runRefresh(f.options, f.dependencies);
  assert.equal(f.requests.length, 1); assert.ok(f.requests[0].endsWith("/200"));
  const state = await readJson(path.join(f.options.stateDir, "runs", `${saved.runId}.json`));
  assert.deepEqual(state.observations[100], observation); assert.equal(r.candidate.datasetVersion, base.datasetVersion + 1);
  // Recovery also repairs a missing report after an interrupted candidate replacement.
  await rm(f.options.report);
  f.requests.length = 0; await runRefresh(f.options, f.dependencies);
  assert.equal(f.requests.length, 0); assert.equal(JSON.parse(await readFile(f.options.report)).candidate.sha256, hash(await readFile(f.options.output)));
});
test("resume rejects baseline/options/mapping/version changes before requests or output mutation", async (t) => {
  const f = await fixture(t); const reviewed = path.join(f.dir, "reviewed.json");
  const mappingText = serializeDataset({ ...base, datasetVersion: 0 }); await writeFile(reviewed, mappingText); f.options.reviewedMappings = reviewed;
  const r = await runRefresh(f.options, f.dependencies); f.options.resume = r.runId;
  const checkpoint = path.join(f.options.stateDir, "runs", `${r.runId}.json`); const stateText = await readFile(checkpoint, "utf8");
  const candidateText = await readFile(f.options.output, "utf8"); const reportText = await readFile(f.options.report, "utf8");
  const cases = [
    async () => writeFile(f.source, serializeDataset({ ...base, datasetVersion: 99 })),
    async () => { f.options.maxRequests++; },
    async () => writeFile(reviewed, serializeDataset({ ...base, datasetVersion: 1 })),
    async () => writeJson(checkpoint, { ...JSON.parse(stateText), stateVersion: 9 }),
  ];
  for (const change of cases) {
    await change(); f.requests.length = 0;
    await assert.rejects(runRefresh(f.options, f.dependencies), /incompatible-resume/); assert.equal(f.requests.length, 0);
    assert.equal(await readFile(f.options.output, "utf8"), candidateText); assert.equal(await readFile(f.options.report, "utf8"), reportText);
    await writeFile(f.source, f.text); f.options.maxRequests = 500; await writeFile(reviewed, mappingText); await writeFile(checkpoint, stateText);
  }
});
test("dry run resolves partial scope with no HLTB, candidate, checkpoint or history mutation", async (t) => {
  const f = await fixture(t, ["--dry-run", "--steam-id", ids[1]]);
  const original = f.dependencies.fetch;
  f.dependencies.fetch = async (url, opts) => url.includes(ids[1]) ? new Response('{"response":{}}') : original(url, opts);
  const r = await runRefresh(f.options, f.dependencies);
  assert.equal(r.exitCode, 2); assert.equal(r.report.outcome, "plan-only"); assert.equal(r.report.candidate, null);
  assert.equal(f.requests.length, 1); assert.ok(f.requests[0].includes("steampowered"));
  await assert.rejects(readdir(f.options.stateDir), { code: "ENOENT" });
  f.options.explicitReport = true; await runRefresh(f.options, f.dependencies);
  assert.deepEqual(await readdir(f.options.stateDir), ["report.json"]);
});
test("source changes and interrupted pair writes preserve source and allow compatible recovery", async (t) => {
  const f = await fixture(t);
  f.dependencies.beforeFinalize = async () => writeFile(f.source, serializeDataset({ ...base, datasetVersion: 99 }));
  await assert.rejects(runRefresh(f.options, f.dependencies), /source-changed/);
  await assert.rejects(readFile(f.options.output), { code: "ENOENT" });
  await writeFile(f.source, f.text); f.dependencies.beforeFinalize = undefined;
  const [file] = await readdir(path.join(f.options.stateDir, "runs")); f.options.resume = file.slice(0, -5);
  f.dependencies.afterCandidateWrite = async () => { throw new Error("pair interrupted"); };
  await assert.rejects(runRefresh(f.options, f.dependencies), /pair interrupted/); await assert.rejects(readFile(f.options.report), { code: "ENOENT" });
  f.dependencies.afterCandidateWrite = undefined; f.requests.length = 0;
  const r = await runRefresh(f.options, f.dependencies); assert.equal(f.requests.length, 0);
  assert.equal(r.report.candidate.sha256, hash(await readFile(f.options.output))); assert.equal(await readFile(f.source, "utf8"), f.text);
});
test("original gather time obeys older-observation precedence and one merge version increment", () => {
  const plan = { addedMappings: [[40, 300]], mappings: [...base.mappings, [40, 300]] };
  const candidate = createCandidate(base, plan, {
    100: { status: "usable", gatheredAt: 1000, lengths: [9, null, null, null] },
    300: { status: "usable", gatheredAt: 1700000010000, lengths: [10, null, null, null] },
  }, { gatheredAt: 1700000001000 });
  assert.deepEqual(candidate.lengths[0], base.lengths[0]); assert.equal(candidate.datasetVersion, 3); assert.equal(candidate.gatheredAt, 1700000010000);
});
test("public output never contains credentialed exceptions/raw-account fields or private paths", async (t) => {
  const f = await fixture(t); let printed = "";
  f.dependencies.stdout = (s) => printed += s; f.dependencies.stderr = (s) => printed += s;
  f.dependencies.fetch = async () => { throw new Error(`${ids[0]} SYNTHETIC_CREDENTIAL C:\\private\\state PRIVATE_ACCOUNT https://steamcommunity.com/RAW`); };
  const args = ["--steam-id", ids[0], "--state-dir", f.options.stateDir];
  assert.equal(await main(args, f.dependencies), 1);
  for (const secret of [ids[0], "SYNTHETIC_CREDENTIAL", "PRIVATE_ACCOUNT", "steamcommunity", f.dir, "C:\\private"]) assert.ok(!printed.includes(secret));
  const report = await readFile(f.options.report, "utf8"); assert.equal(report.includes("SYNTHETIC_CREDENTIAL"), false);
  const cli = spawnSync(process.execPath, ["tools/hltb-dataset/refresh.mjs", "--steam-id", ids[0]], { encoding: "utf8", env: { ...process.env, STEAM_WEB_API_KEY: "" } });
  assert.equal(cli.status, 1); assert.match(cli.stderr, /missing-steam-key/); assert.equal(cli.stderr.includes(ids[0]), false);
});
test("request exhaustion distinguishes failed from unattempted and retains every existing row", async (t) => {
  const f = await fixture(t, ["--max-requests", "1"]);
  const r = await runRefresh(f.options, f.dependencies);
  assert.equal(r.exitCode, 2); assert.equal(r.report.requests.budgetExhausted, true);
  assert.deepEqual(r.report.failures, []); assert.deepEqual(r.report.unattempted, [100, 200]);
  assert.deepEqual(r.candidate, base);
});
