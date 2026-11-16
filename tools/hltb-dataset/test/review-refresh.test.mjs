import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdir, mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { parseOptions, TOOL_DIR } from '../refresh-options.mjs';
import { runRefresh } from '../refresh-run.mjs';
import { serializeDataset } from '../merge.mjs';
import { hash } from '../refresh-storage.mjs';
import { PREVIEW_WARNING } from '../review.mjs';
import { verifyPair } from '../../../.claude/skills/hltb-dataset-refresh/scripts/verify-pair.mjs';
const steam = '76561198000000001';
const candidate = (hltbId, decision = 'approved') => ({ hltbId, hltbTitle: 'Fixture Game', previewMinutes: [999, 999, 999, 999], source: 'primary', query: 'Fixture Game', score: 1, decision });
const app = (appId, hltbId, decision) => ({ appId, steamTitle: 'Fixture Game', outcome: 'candidates', category: null, candidates: [candidate(hltbId, decision)] });
function page(id, seconds = 12000) {
  return `<script id="__NEXT_DATA__" type="application/json">${JSON.stringify({ props: { pageProps: { game: { game_id: id, game_name: 'Fixture Game', comp_main: seconds } } } })}</script>`;
}
async function fixture(t, apps = [app(40, 100), app(50, 300, 'pending'), app(60, 100, 'rejected'), app(70, 400)]) {
  await mkdir(path.join(TOOL_DIR, '.local'), { recursive: true });
  const dir = await mkdtemp(path.join(TOOL_DIR, '.local', 'review-refresh-')); t.after(() => rm(dir, { recursive: true, force: true }));
  const srcDir = await mkdtemp(path.join(os.tmpdir(), 'review-source-')); t.after(() => rm(srcDir, { recursive: true, force: true }));
  const source = path.join(srcDir, 'source.json');
  const base = { schemaVersion: 1, datasetVersion: 1, gatheredAt: 1700000000000, mappings: [[10, 100], [20, 100], [30, 200]], lengths: [[100, 100, null, null, null], [200, 50, null, null, null]] };
  const text = serializeDataset(base); await writeFile(source, text);
  const review = { reviewVersion: 1, discoveryId: 'a'.repeat(32), baselineHash: hash(text), createdAt: 1700000000001, scopeHash: 'b'.repeat(64),
    libraries: { requested: 1, readable: 1, unavailable: 0, empty: 0 }, requests: { attempts: 3, retries: 0, elapsedMs: 2000 }, warning: PREVIEW_WARNING, apps };
  const reviewFile = path.join(dir, 'review.json'); await writeFile(reviewFile, JSON.stringify(review));
  const options = parseOptions(['--steam-id', steam, '--review-candidates', reviewFile, '--state-dir', path.join(dir, 'state'), '--output', path.join(dir, 'candidate.json'), '--report', path.join(dir, 'report.json')]);
  let time = 1700000100000; const calls = [];
  const dependencies = { source, env: { STEAM_WEB_API_KEY: 'SYNTHETIC_CREDENTIAL' }, now: () => time, sleep: async ms => { time += ms; }, random: () => 0,
    fetch: async url => { calls.push(url); if (url.includes('steampowered')) return new Response(JSON.stringify({ response: { game_count: 3, games: [40, 50, 60].map(appid => ({ appid, name: 'Fixture Game' })) } }));
      if (url === 'https://howlongtobeat.com/game/100') return new Response(page(100)); throw Error('Unexpected network request'); } };
  return { dir, source, base, text, review, reviewFile, options, dependencies, calls };
}
test('approved exact pairs intersect current scope; shared lengths require one fresh read even in missing-only/stale', async t => {
  for (const mode of ['missing-only', 'stale']) {
    const f = await fixture(t); f.options.mode = mode; if (mode === 'stale') f.options.maxAgeDays = 1;
    const r = await runRefresh(f.options, f.dependencies);
    assert.deepEqual(r.report.addedMappings, [[40, 100]]); assert.deepEqual(r.report.plannedHltbIds, [100]);
    assert.equal(f.calls.filter(u => u.includes('/game/')).length, 1);
    assert.deepEqual(r.candidate.lengths[0], [100, 200, null, null, null]);
    assert.equal(r.report.review.approvedPairCount, 2); assert.equal(r.report.review.rejectedPairCount, 1); assert.equal(r.report.review.pendingPairCount, 1);
    assert.deepEqual(r.report.review.excludedPairs, [{ appId: 70, hltbId: 400 }]);
    assert.deepEqual(r.report.affectedApps.map(a => a.appId), [10, 20, 40]);
    assert.deepEqual(r.report.unmapped.map(a => a.appId), [50, 60]);
    assert.equal(r.report.checked.usableEntries, 1); assert.equal(r.report.toolVersion, 1); assert.equal(r.report.reportVersion, 1);
    assert.equal(verifyPair(f.text, await readFile(f.options.output, 'utf8'), r.report).verified, true);
    assert.equal(await readFile(f.source, 'utf8'), f.text);
    const state = JSON.parse(await readFile(path.join(f.options.stateDir, 'runs', `${r.runId}.json`)));
    assert.equal(state.mappingKind, 'review'); assert.equal(state.mappingHash, hash(await readFile(f.reviewFile)));
    assert.ok(!JSON.stringify(state).includes('999')); assert.ok(!JSON.stringify(r.report).includes(f.dir));
  }
});
test('conflicting approvals block globally even outside scope; stale baseline and invalid inputs fail before network', async t => {
  const f = await fixture(t, [app(10, 999)]);
  const r = await runRefresh(f.options, f.dependencies);
  assert.equal(r.exitCode, 1); assert.deepEqual(r.report.mappingConflicts, [{ appId: 10, existingHltbId: 100, reviewedHltbId: 999 }]);
  await assert.rejects(readFile(f.options.output), { code: 'ENOENT' }); assert.ok(f.calls.every(u => u.includes('steampowered')));
  for (const mutate of [r => r.baselineHash = 'c'.repeat(64), r => r.apps[0].candidates[0].decision = 'auto']) {
    const review = structuredClone(f.review); mutate(review); await writeFile(f.reviewFile, JSON.stringify(review)); f.calls.length = 0;
    await assert.rejects(runRefresh(f.options, f.dependencies)); assert.equal(f.calls.length, 0);
  }
  assert.throws(() => parseOptions(['--steam-id', steam, '--review-candidates', f.reviewFile, '--reviewed-mappings', f.source]), /review-input-conflict/);
});
test('no approved pair authorizes neither mapping nor request; restriction excludes approvals', async t => {
  const f = await fixture(t, [app(40, 100, 'pending'), app(50, 300, 'rejected')]);
  let r = await runRefresh(f.options, f.dependencies); assert.deepEqual(r.candidate, f.base); assert.deepEqual(r.report.plannedHltbIds, []);
  assert.ok(f.calls.every(u => u.includes('steampowered')));
  f.review.apps[0].candidates[0].decision = 'approved'; await writeFile(f.reviewFile, JSON.stringify(f.review));
  f.options.appIds = [50, 999]; f.calls.length = 0;
  r = await runRefresh(f.options, f.dependencies); assert.deepEqual(r.report.addedMappings, []); assert.equal(r.report.review.excludedPairCount, 1);
  assert.ok(f.calls.every(u => u.includes('steampowered')));
});
test('mapping-only approvals preserve failed/no-length/unattempted baseline rows and successful history', async t => {
  for (const failure of ['empty', 'failed', 'unattempted']) {
    const f = await fixture(t, [app(40, 100)]);
    if (failure === 'unattempted') f.options.maxRequests = 1;
    else { const prior = f.dependencies.fetch; f.dependencies.fetch = async url => url.includes('/game/') ? new Response(failure === 'empty' ? page(100, 0) : 'denied', { status: failure === 'empty' ? 200 : 403 }) : prior(url); }
    const r = await runRefresh(f.options, f.dependencies);
    assert.deepEqual(r.report.addedMappings, [[40, 100]]); assert.deepEqual(r.candidate.lengths, f.base.lengths); assert.equal(r.report.checked.usableEntries, 0);
    assert.equal(r.report[failure === 'empty' ? 'noLengths' : failure === 'failed' ? 'failures' : 'unattempted'].length, 1);
    assert.deepEqual(JSON.parse(await readFile(path.join(f.options.stateDir, 'history.json'))).entries, {});
    assert.equal(verifyPair(f.text, await readFile(f.options.output, 'utf8'), r.report).verified, true);
  }
});
test('review bytes bind resume, including withdrawal/title/rejected decision edits, and input-kind cannot masquerade', async t => {
  const f = await fixture(t); const r = await runRefresh(f.options, f.dependencies); f.options.resume = r.runId;
  const original = await readFile(f.reviewFile, 'utf8'); const output = await readFile(f.options.output, 'utf8');
  for (const mutate of [r => r.apps[0].candidates[0].decision = 'rejected', r => r.apps[0].steamTitle = 'Changed', r => r.apps[2].candidates[0].decision = 'pending']) {
    const review = JSON.parse(original); mutate(review); await writeFile(f.reviewFile, JSON.stringify(review)); f.calls.length = 0;
    await assert.rejects(runRefresh(f.options, f.dependencies), /incompatible-resume/); assert.equal(f.calls.length, 0); assert.equal(await readFile(f.options.output, 'utf8'), output);
  }
  await writeFile(f.reviewFile, original);
  const stateFile = path.join(f.options.stateDir, 'runs', `${r.runId}.json`); const state = JSON.parse(await readFile(stateFile)); state.mappingKind = 'legacy'; await writeFile(stateFile, JSON.stringify(state));
  await assert.rejects(runRefresh(f.options, f.dependencies), /incompatible-resume/);
});
test('optional setup diagnostics preserve approved refresh input and full-byte resume binding', async t => {
  const f = await fixture(t);
  f.review.sessionFailure = { phase: 'session-init', category: 'access-denied', status: 403 };
  const original = JSON.stringify(f.review); await writeFile(f.reviewFile, original);
  const r = await runRefresh(f.options, f.dependencies);
  assert.deepEqual(r.report.addedMappings, [[40, 100]]);
  assert.deepEqual(r.report.review.sessionFailure, f.review.sessionFailure);
  assert.equal(await readFile(f.reviewFile, 'utf8'), original);
  f.options.resume = r.runId; f.calls.length = 0;
  await runRefresh(f.options, f.dependencies); assert.equal(f.calls.length, 0);
  f.review.sessionFailure.status = 401; await writeFile(f.reviewFile, JSON.stringify(f.review));
  await assert.rejects(runRefresh(f.options, f.dependencies), /incompatible-resume/);
  assert.equal(f.calls.length, 0);
});
test('approval withdrawal during fetch blocks finalization, preserves observations and resume identity', async t => {
  const f = await fixture(t); const original = await readFile(f.reviewFile, 'utf8'); let saved;
  f.dependencies.afterCheckpoint = async state => { saved = structuredClone(state); f.review.apps[0].candidates[0].decision = 'rejected'; await writeFile(f.reviewFile, JSON.stringify(f.review)); };
  await assert.rejects(runRefresh(f.options, f.dependencies), /review-input-changed/);
  await assert.rejects(readFile(f.options.output), { code: 'ENOENT' });
  f.options.resume = saved.runId; f.calls.length = 0; delete f.dependencies.afterCheckpoint;
  await assert.rejects(runRefresh(f.options, f.dependencies), /incompatible-resume/); assert.equal(f.calls.length, 0);
  await writeFile(f.reviewFile, original); const r = await runRefresh(f.options, f.dependencies); assert.equal(f.calls.length, 0);
  const resumed = JSON.parse(await readFile(path.join(f.options.stateDir, 'runs', `${r.runId}.json`)));
  assert.deepEqual(resumed.observations, saved.observations);
});
test('baseline race blocks output and interrupted pair resumes without fetching or retimestamping', async t => {
  const f = await fixture(t); f.dependencies.beforeFinalize = async () => writeFile(f.source, serializeDataset({ ...f.base, datasetVersion: 2 }));
  await assert.rejects(runRefresh(f.options, f.dependencies), /source-changed/); await assert.rejects(readFile(f.options.output), { code: 'ENOENT' });
  await writeFile(f.source, f.text); delete f.dependencies.beforeFinalize;
  let state; f.dependencies.afterCheckpoint = async s => { state = structuredClone(s); };
  f.dependencies.afterCandidateWrite = async () => { throw Error('interrupted pair'); };
  await assert.rejects(runRefresh(f.options, f.dependencies), /interrupted pair/); await assert.rejects(readFile(f.options.report), { code: 'ENOENT' });
  f.options.resume = state.runId; delete f.dependencies.afterCandidateWrite; f.calls.length = 0;
  const r = await runRefresh(f.options, f.dependencies); assert.equal(f.calls.length, 0);
  assert.equal(verifyPair(f.text, await readFile(f.options.output, 'utf8'), r.report).verified, true);
  assert.deepEqual(JSON.parse(await readFile(path.join(f.options.stateDir, 'runs', `${r.runId}.json`))).observations, state.observations);
});
test('legacy reviewed additions sharing existing lengths also require direct reads', async t => {
  const f = await fixture(t); delete f.options.reviewCandidates; f.options.reviewedMappings = path.join(path.dirname(f.source), 'legacy.json');
  await writeFile(f.options.reviewedMappings, serializeDataset({ ...f.base, mappings: [[40, 100]], lengths: [] }));
  const r = await runRefresh(f.options, f.dependencies); assert.deepEqual(r.report.plannedHltbIds, [100]); assert.deepEqual(r.report.addedMappings, [[40, 100]]);
  assert.ok(!Object.hasOwn(r.report, 'review'));
});
test('legacy version-1 checkpoints without input-kind field remain compatible', async t => {
  const f = await fixture(t); delete f.options.reviewCandidates;
  const r = await runRefresh(f.options, f.dependencies);
  const checkpoint = path.join(f.options.stateDir, 'runs', `${r.runId}.json`);
  const state = JSON.parse(await readFile(checkpoint)); delete state.mappingKind; await writeFile(checkpoint, JSON.stringify(state));
  f.options.resume = r.runId; f.calls.length = 0;
  await runRefresh(f.options, f.dependencies); assert.equal(f.calls.length, 0);
});
