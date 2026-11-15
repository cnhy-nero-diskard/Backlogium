import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, writeFile, rm, readdir } from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { parseDiscoveryOptions, runDiscovery, main } from '../discover.mjs';
import { TOOL_DIR } from '../refresh-options.mjs';
import { serializeDataset } from '../merge.mjs';
export const steamIds = ['76561198000000001', '76561198000000002'];
export async function discoveryFixture(t, tail = [], games = [{ appid: 10, name: 'Covered' }, { appid: 40, name: 'Game 2' }, { appid: 50, name: 'Other' }]) {
  await mkdir(path.join(TOOL_DIR, '.local'), { recursive: true });
  const dir = await mkdtemp(path.join(TOOL_DIR, '.local', 'discovery-test-')); t.after(() => rm(dir, { recursive: true, force: true }));
  const sourceDir = await mkdtemp(path.join(os.tmpdir(), 'discovery-source-')); t.after(() => rm(sourceDir, { recursive: true, force: true }));
  const source = path.join(sourceDir, 'source.json');
  const base = { schemaVersion: 1, datasetVersion: 1, gatheredAt: 1700000000000, mappings: [[10, 100]], lengths: [[100, 10, null, null, null]] };
  const text = serializeDataset(base); await writeFile(source, text);
  const options = parseDiscoveryOptions(['--steam-id', steamIds[0], '--output', path.join(dir, 'review.json'), '--report', path.join(dir, 'report.json'), ...tail]);
  let time = 1700000100000; const calls = [];
  const dependencies = { source, env: { STEAM_WEB_API_KEY: 'SYNTHETIC_CREDENTIAL' }, now: () => time, sleep: async ms => { time += ms; }, random: () => 0,
    fetch: async (url, opts) => {
      calls.push({ url, opts });
      if (url.includes('steampowered')) return new Response(JSON.stringify({ response: { game_count: games.length, games } }));
      if (url.includes('/init?')) return new Response('{"token":"fixture-session"}');
      if (opts.method === 'POST') return new Response('{"data":[{"game_id":300,"game_name":"Game 2","comp_main":90}]}');
      throw Error('Unexpected request');
    } };
  return { dir, source, base, text, options, dependencies, calls };
}
test('discovery unions/intersects readable libraries, searches uncovered owned apps once and preserves input', async t => {
  const f = await discoveryFixture(t, ['--steam-id', steamIds[1], '--app-id', '10', '--app-id', '40', '--app-id', '999']);
  const r = await runDiscovery(f.options, f.dependencies);
  assert.deepEqual(r.review.apps.map(a => a.appId), [40]); assert.equal(r.review.apps[0].candidates[0].decision, 'pending');
  assert.equal(f.calls.filter(c => c.opts.method === 'POST').length, 1); assert.equal(r.report.review.candidatePairs, 1);
  assert.ok(f.calls.every(c => !c.url.includes('/game/'))); assert.equal(await readFile(f.source, 'utf8'), f.text);
  assert.ok(!(await readdir(f.dir)).includes('history.json'));
  const persisted = await readFile(f.options.output, 'utf8');
  for (const secret of ['SYNTHETIC_CREDENTIAL', 'fixture-session', ...steamIds]) assert.ok(!persisted.includes(secret) && !JSON.stringify(r.report).includes(secret));
  await assert.rejects(runDiscovery(f.options, f.dependencies), /review-already-exists/);
  assert.equal(await readFile(f.options.output, 'utf8'), persisted);
});
test('unavailable and empty libraries remain distinct; no readable scope never searches', async t => {
  const f = await discoveryFixture(t, ['--steam-id', steamIds[1]]);
  f.dependencies.fetch = async (url) => { f.calls.push({ url }); return new Response(url.includes(steamIds[0]) ? '{"response":{"game_count":0}}' : '{"response":{}}'); };
  const r = await runDiscovery(f.options, f.dependencies);
  assert.deepEqual(r.report.libraries, { requested: 2, readable: 1, unavailable: 1, empty: 1 }); assert.equal(r.exitCode, 2);
  await rm(f.options.output);
  f.dependencies.fetch = async () => new Response('{"response":{}}');
  assert.equal((await runDiscovery(f.options, f.dependencies)).exitCode, 1);
  await assert.rejects(readFile(f.options.output), { code: 'ENOENT' });
});
test('dry-run does only Steam reads, writes only explicit plan report', async t => {
  const f = await discoveryFixture(t, ['--dry-run']); f.options.explicitReport = false;
  const r = await runDiscovery(f.options, f.dependencies); assert.equal(r.report.outcome, 'plan-only');
  assert.equal(f.calls.length, 1); assert.ok(f.calls[0].url.includes('steampowered'));
  assert.deepEqual(await readdir(f.dir), []);
  f.options.explicitReport = true; await runDiscovery(f.options, f.dependencies);
  assert.deepEqual((await readdir(f.dir)).sort(), ['report.json']);
});
test('broader is opt-in only after empty primary, bound to three queries with original scoring', async t => {
  for (const broader of [false, true]) {
    const f = await discoveryFixture(t, broader ? ['--broader'] : [], [{ appid: 40, name: 'The Game 2: Subtitle Deluxe Edition' }]);
    const prior = f.dependencies.fetch; let posts = 0;
    f.dependencies.fetch = async (url, opts) => {
      if (opts.method === 'POST') { f.calls.push({ url, opts }); posts++; return new Response(posts === 1 ? '{"data":[]}' : '{"data":[{"game_id":300,"game_name":"Game 2"}]}'); }
      return prior(url, opts);
    };
    const r = await runDiscovery(f.options, f.dependencies);
    assert.equal(posts, broader ? 4 : 1); assert.equal(r.review.apps[0].outcome, broader ? 'candidates' : 'unmatched');
    if (broader) { assert.equal(r.review.apps[0].candidates.length, 1); assert.ok(r.review.apps[0].candidates[0].score < 1); }
  }
});
test('search errors, missing titles, budget stops and unmatched apps use separate units', async t => {
  const f = await discoveryFixture(t, ['--broader'], [{ appid: 40, name: 'Missing' }, { appid: 50 }, { appid: 60, name: 'Third' }, { appid: 70, name: 'Last' }]);
  const prior = f.dependencies.fetch; let n = 0;
  f.dependencies.fetch = async (url, opts) => {
    if (opts.method === 'POST') { f.calls.push({ url, opts }); n++; return n === 1 ? new Response('{"data":[]}') : new Response('denied', { status: 403 }); }
    return prior(url, opts);
  };
  const r = await runDiscovery(f.options, f.dependencies);
  assert.deepEqual(r.review.apps.map(a => a.outcome), ['unmatched', 'failed', 'failed', 'unattempted']);
  assert.equal(r.report.review.failedApps.length, 2); assert.equal(r.report.review.unattemptedApps.length, 1);
  assert.equal(n, 2); assert.equal(r.exitCode, 2);
});
test('controlled broader failure preserves pending results and reports partial search separately', async t => {
  const f = await discoveryFixture(t, ['--broader'], [{ appid: 40, name: 'The Game 2: Subtitle Deluxe Edition' }]);
  const prior = f.dependencies.fetch; let n = 0;
  f.dependencies.fetch = async (url, opts) => {
    if (opts.method === 'POST') { n++; return n === 1 ? new Response('{"data":[]}') : n === 2 ? new Response('{"data":[{"game_id":300,"game_name":"Game 2"}]}') : new Response('denied', { status: 403 }); }
    return prior(url, opts);
  };
  const r = await runDiscovery(f.options, f.dependencies);
  assert.equal(r.review.apps[0].outcome, 'candidates'); assert.equal(r.report.review.partialSearchFailures.length, 1);
  assert.equal(r.review.apps[0].candidates[0].decision, 'pending'); assert.equal(n, 3);
});
test('inspection is offline and derives current decisions without trusting old report', async t => {
  const f = await discoveryFixture(t); await runDiscovery(f.options, f.dependencies);
  const review = JSON.parse(await readFile(f.options.output)); review.apps[0].candidates[0].decision = 'approved'; review.apps[1].candidates[0].decision = 'rejected';
  const edited = JSON.stringify(review); await writeFile(f.options.output, edited);
  const dependencies = { source: f.source, env: {}, fetch: () => { throw Error('NO NETWORK'); } };
  const options = parseDiscoveryOptions(['--inspect', f.options.output]);
  const r = await runDiscovery(options, dependencies);
  assert.equal(r.report.review.approvedPairCount, 1); assert.equal(r.report.review.rejectedPairCount, 1);
  assert.equal(JSON.parse(await readFile(f.options.report)).review.approvedPairCount, 0);
  assert.equal(await readFile(f.options.output, 'utf8'), edited);
  options.report = path.join(f.dir, 'inspection.json'); options.explicitReport = true;
  await runDiscovery(options, dependencies); assert.equal(JSON.parse(await readFile(options.report)).review.approvedPairCount, 1);
  for (const args of [['--inspect', f.options.output, '--steam-id', steamIds[0]], ['--inspect', f.options.output, '--dry-run'], ['--inspect', f.options.output, '--broader']]) assert.throws(() => parseDiscoveryOptions(args), /inspect-option-conflict/);
  review.apps[0].candidates[0].decision = 'automatic'; await writeFile(f.options.output, JSON.stringify(review));
  await assert.rejects(runDiscovery(options, dependencies), /invalid-review/);
  let error; assert.equal(await main(['--inspect', f.options.output], { ...dependencies, stderr: s => { error = s; } }), 1); assert.ok(!error.includes(f.dir));
});
