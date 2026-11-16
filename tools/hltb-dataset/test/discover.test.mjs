import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, writeFile, rm, readdir } from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { parseDiscoveryOptions, runDiscovery, main } from '../discover.mjs';
import { TOOL_DIR } from '../refresh-options.mjs';
import { serializeDataset } from '../merge.mjs';
import { parseReview } from '../review.mjs';
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
test('initial setup failures are blocked, all searches unattempted, and setup is not repeated', async t => {
  const cases = [
    [401, '{"error":"PRIVATE"}', 'access-denied', 1, 401],
    [403, '{"error":"PRIVATE"}', 'access-denied', 1, 403],
    [200, 'Just a moment cf-chl- PRIVATE', 'challenge-response', 1, 200],
    [200, '{"error":"PRIVATE"}', 'invalid-session', 1, null],
    [429, 'PRIVATE', 'rate-limited', 3, 429],
    [500, 'PRIVATE', 'server-failed', 3, 500],
    [null, 'PRIVATE', 'transport-failed', 3, null],
  ];
  for (const [status, body, category, attempts, diagnosticStatus] of cases) {
    const f = await discoveryFixture(t, ['--broader']); const prior = f.dependencies.fetch;
    f.dependencies.fetch = async (url, opts) => {
      if (url.includes('steampowered')) return prior(url, opts);
      f.calls.push({ url, opts }); assert.ok(url.includes('/init?')); assert.equal(opts.method, 'GET');
      if (status === null) throw Error(body);
      return new Response(body, { status });
    };
    const r = await runDiscovery(f.options, f.dependencies);
    assert.equal(r.exitCode, 1); assert.equal(r.report.outcome, 'blocked');
    assert.deepEqual(r.review.apps.map(a => a.outcome), ['unattempted', 'unattempted']);
    assert.deepEqual(r.report.review.failedApps, []); assert.equal(r.report.review.unattemptedApps.length, 2);
    assert.deepEqual(r.report.review.sessionFailure, { phase: 'session-init', category, status: diagnosticStatus });
    assert.equal(f.calls.length, 1 + attempts);
    assert.deepEqual(parseReview(await readFile(f.options.output, 'utf8')), r.review);
    assert.equal(await readFile(f.source, 'utf8'), f.text);
    const inspected = await runDiscovery(parseDiscoveryOptions(['--inspect', f.options.output]), {
      source: f.source, env: {}, fetch: () => { throw Error('NO NETWORK'); },
    });
    assert.deepEqual(inspected.report.review.sessionFailure, r.review.sessionFailure);
    for (const secret of ['PRIVATE', 'SYNTHETIC_CREDENTIAL', ...steamIds, f.dir, 'howlongtobeat.com']) {
      assert.ok(!JSON.stringify(r.review).includes(secret)); assert.ok(!JSON.stringify(r.report).includes(secret));
    }
    assert.ok(!(await readdir(f.dir)).includes('history.json'));
  }
});
test('endpoint setup failure reports the actual phase and never reaches a search POST', async t => {
  for (const stage of ['homepage', 'chunk', 'alternate-init', 'absent']) {
    const f = await discoveryFixture(t); const prior = f.dependencies.fetch;
    f.dependencies.fetch = async (url, opts) => {
      if (url.includes('steampowered')) return prior(url, opts);
      f.calls.push({ url, opts }); assert.equal(opts.method, 'GET');
      if (url.includes('/search/site/init?')) return new Response('absent', { status: 404 });
      if (url.endsWith('/')) return new Response(stage === 'homepage' ? 'PRIVATE' : '"/_next/static/chunks/a.js"', { status: stage === 'homepage' ? 403 : 200 });
      if (url.endsWith('.js')) return new Response(stage === 'chunk' ? 'PRIVATE' : stage === 'absent' ? '' : 'fetch(`/api/other/init?t=1`)', { status: stage === 'chunk' ? 403 : 200 });
      return new Response('PRIVATE', { status: 403 });
    };
    const r = await runDiscovery(f.options, f.dependencies);
    assert.equal(r.exitCode, 1); assert.equal(r.report.outcome, 'blocked');
    assert.deepEqual(r.review.sessionFailure, {
      phase: stage === 'alternate-init' ? 'session-init' : 'endpoint-discovery',
      category: stage === 'absent' ? 'endpoint-absent' : 'access-denied', status: stage === 'absent' ? null : 403,
    });
    assert.ok(r.review.apps.every(a => a.outcome === 'unattempted'));
    assert.equal(f.calls.length, stage === 'homepage' ? 3 : stage === 'alternate-init' ? 5 : 4);
  }
});
test('renewal failure retains earlier candidates and unmatched results, leaving later apps unattempted', async t => {
  const f = await discoveryFixture(t, [], [40, 50, 60, 70].map(appid => ({ appid, name: 'Fixture Game' })));
  const prior = f.dependencies.fetch, originalNow = f.dependencies.now; let offset = 0, inits = 0, posts = 0;
  f.dependencies.now = () => originalNow() + offset;
  f.dependencies.fetch = async (url, opts) => {
    if (url.includes('steampowered')) return prior(url, opts);
    f.calls.push({ url, opts });
    if (url.includes('/init?')) return new Response(++inits === 1 ? '{"token":"PRIVATE_SESSION"}' : '{"error":"PRIVATE"}', { status: inits === 1 ? 200 : 403 });
    assert.equal(opts.method, 'POST'); posts++;
    if (posts === 2) { offset += 300001; return new Response('{"data":[]}'); }
    return new Response('{"data":[{"game_id":300,"game_name":"Fixture Game"}]}');
  };
  const r = await runDiscovery(f.options, f.dependencies);
  assert.equal(r.exitCode, 2); assert.equal(r.report.outcome, 'partial');
  assert.deepEqual(r.review.apps.map(a => a.outcome), ['candidates', 'unmatched', 'unattempted', 'unattempted']);
  assert.equal(r.review.apps[0].candidates[0].decision, 'pending');
  assert.deepEqual(r.review.sessionFailure, { phase: 'session-init', category: 'access-denied', status: 403 });
  assert.equal(posts, 2); assert.equal(inits, 2); assert.equal(f.calls.length, 5);
  assert.ok(!JSON.stringify(r).includes('PRIVATE'));
  r.review.apps[0].candidates[0].decision = 'approved';
  const edited = JSON.stringify(r.review); await writeFile(f.options.output, edited);
  const inspected = await runDiscovery(parseDiscoveryOptions(['--inspect', f.options.output]), {
    source: f.source, env: {}, fetch: () => { throw Error('NO NETWORK'); },
  });
  assert.equal(inspected.report.review.approvedPairCount, 1);
  assert.deepEqual(inspected.report.review.sessionFailure, r.review.sessionFailure);
  assert.deepEqual(inspected.report.review.unmatchedApps, [50]);
  assert.deepEqual(inspected.report.review.unattemptedApps, [60, 70]);
  assert.equal(await readFile(f.options.output, 'utf8'), edited);
});
test('setup expiry during broader search preserves prior suggestions and partial search failure', async t => {
  const f = await discoveryFixture(t, ['--broader'], [{ appid: 40, name: 'The Game 2: Subtitle Deluxe Edition' }]);
  const prior = f.dependencies.fetch, originalNow = f.dependencies.now; let offset = 0, inits = 0, posts = 0;
  f.dependencies.now = () => originalNow() + offset;
  f.dependencies.fetch = async (url, opts) => {
    if (url.includes('steampowered')) return prior(url, opts);
    f.calls.push({ url, opts });
    if (url.includes('/init?')) return new Response(++inits === 1 ? '{"token":"fixture"}' : 'denied', { status: inits === 1 ? 200 : 403 });
    posts++; if (posts === 1) return new Response('{"data":[]}');
    offset += 300001; return new Response('{"data":[{"game_id":300,"game_name":"Game 2"}]}');
  };
  const r = await runDiscovery(f.options, f.dependencies);
  assert.equal(r.exitCode, 2); assert.equal(r.review.apps[0].outcome, 'candidates');
  assert.equal(r.review.apps[0].candidates[0].decision, 'pending');
  assert.equal(r.report.review.partialSearchFailures.length, 1);
  assert.equal(r.review.sessionFailure.phase, 'session-init'); assert.equal(posts, 2); assert.equal(inits, 2);
});
test('budget stops distinguish setup from a refused POST and a denied POST is an app failure', async t => {
  for (const max of [1, 2, 3]) {
    const f = await discoveryFixture(t, ['--max-requests', String(max)]);
    const prior = f.dependencies.fetch;
    f.dependencies.fetch = async (url, opts) => {
      if (opts.method !== 'POST') return prior(url, opts);
      f.calls.push({ url, opts }); return new Response('PRIVATE', { status: 403 });
    };
    const r = await runDiscovery(f.options, f.dependencies);
    assert.equal(r.exitCode, max === 1 ? 1 : 2);
    assert.deepEqual(r.review.apps.map(a => a.outcome), [max === 3 ? 'failed' : 'unattempted', 'unattempted']);
    if (max === 1) assert.deepEqual(r.review.sessionFailure, { phase: 'session-init', category: 'budget-exhausted', status: null });
    else assert.ok(!Object.hasOwn(r.review, 'sessionFailure'));
    assert.equal(f.calls.length, max);
  }
});
test('covered, empty and unsafe-title-only scope never initializes a session', async t => {
  for (const games of [[], [{ appid: 10, name: 'Covered' }], [{ appid: 40, name: 'token=PRIVATE' }]]) {
    const f = await discoveryFixture(t, [], games);
    const r = await runDiscovery(f.options, f.dependencies);
    assert.equal(f.calls.length, 1); assert.ok(!Object.hasOwn(r.review, 'sessionFailure'));
    assert.equal(r.exitCode, games[0]?.appid === 40 ? 2 : 0);
  }
});
test('CLI returns blocked exit 1 and sanitized session diagnostics on init denial', async t => {
  const f = await discoveryFixture(t); const prior = f.dependencies.fetch; let output;
  f.dependencies.fetch = async (url, opts) => url.includes('steampowered') ? prior(url, opts) : new Response('{"error":"PRIVATE"}', { status: 403 });
  const exit = await main(['--steam-id', steamIds[0], '--output', f.options.output, '--report', f.options.report], {
    ...f.dependencies, stdout: s => { output = s; }, stderr: () => { throw Error('unexpected stderr'); },
  });
  assert.equal(exit, 1); assert.equal(JSON.parse(output).outcome, 'blocked');
  assert.equal(JSON.parse(output).review.sessionFailure.status, 403); assert.ok(!output.includes('PRIVATE'));
});
