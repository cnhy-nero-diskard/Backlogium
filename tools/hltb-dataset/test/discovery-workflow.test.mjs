import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdir, mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { parseDiscoveryOptions, runDiscovery } from '../discover.mjs';
import { parseOptions, TOOL_DIR } from '../refresh-options.mjs';
import { runRefresh } from '../refresh-run.mjs';
import { serializeDataset } from '../merge.mjs';
import { verifyPair } from '../../../.claude/skills/hltb-dataset-refresh/scripts/verify-pair.mjs';
test('end-to-end discovery, partial human approval, direct fetch and full pair verification never promote previews', async t => {
  await mkdir(path.join(TOOL_DIR, '.local'), { recursive: true });
  const dir = await mkdtemp(path.join(TOOL_DIR, '.local', 'workflow-test-')); t.after(() => rm(dir, { recursive: true, force: true }));
  const sourceDir = await mkdtemp(path.join(os.tmpdir(), 'workflow-source-')); t.after(() => rm(sourceDir, { recursive: true, force: true }));
  const source = path.join(sourceDir, 'source.json');
  const base = { schemaVersion: 1, datasetVersion: 2, gatheredAt: 1700000000000, mappings: [[10, 100], [20, 100], [30, 200]], lengths: [[100, 100, null, null, null], [200, 50, null, null, null]] };
  const baselineText = serializeDataset(base); await writeFile(source, baselineText);
  const games = [10, 40, 50, 60, 70, 80, 90, 100].map(appid => ({ appid, name: `Game ${appid}` }));
  const resultIds = new Map([[40, 100], [50, 300], [60, 400], [70, 100], [90, 500], [100, 600]]);
  const requests = []; const unexpected = []; let now = 1700000100000;
  const dependencies = { source, env: { STEAM_WEB_API_KEY: 'SYNTHETIC_CREDENTIAL' }, now: () => now, sleep: async ms => { now += ms; }, random: () => 0,
    fetch: async (url, opts) => {
      requests.push({ url, opts });
      if (url.startsWith('https://api.steampowered.com/IPlayerService/GetOwnedGames/v1/')) return new Response(JSON.stringify({ response: { game_count: games.length, games } }));
      if (url.startsWith('https://howlongtobeat.com/api/search/site/init?t=')) return new Response('{"token":"fixture-session"}');
      if (url === 'https://howlongtobeat.com/api/search/site' && opts.method === 'POST') {
        const body = JSON.parse(opts.body); const appId = Number(body.searchTerms.at(-1));
        const id = resultIds.get(appId);
        return new Response(JSON.stringify({ data: id ? [{ game_id: id, game_name: `Game ${appId}`, comp_main: 59940 }] : [] }));
      }
      const direct = /^https:\/\/howlongtobeat.com\/game\/(100|300|500|600)$/.exec(url);
      if (direct && opts.method === 'GET') {
        const id = Number(direct[1]);
        return new Response(`<script id="__NEXT_DATA__" type="application/json">${JSON.stringify({ props: { pageProps: { game: {
          game_id: id === 600 ? 601 : id, game_name: 'Direct observation', comp_main: id === 500 ? 0 : id === 300 ? 18000 : 12000,
        } } } })}</script>`);
      }
      unexpected.push(url); throw new Error('Unexpected network request');
    } };
  const discoveryOptions = parseDiscoveryOptions(['--steam-id', '76561198000000001', '--output', path.join(dir, 'review.json'), '--report', path.join(dir, 'discovery-report.json')]);
  const discovered = await runDiscovery(discoveryOptions, dependencies);
  assert.equal(discovered.exitCode, 2); assert.deepEqual(discovered.report.review.unmatchedApps, [80]);
  assert.equal(requests.filter(r => r.opts.method === 'POST').length, 7);
  assert.ok(requests.every(r => !r.url.includes('/game/'))); assert.ok(discovered.review.apps.every(a => a.candidates.every(c => c.decision === 'pending')));
  const approved = new Set([40, 50, 90, 100]);
  for (const a of discovered.review.apps) for (const c of a.candidates) c.decision = approved.has(a.appId) ? 'approved' : a.appId === 70 ? 'rejected' : 'pending';
  await writeFile(discoveryOptions.output, JSON.stringify(discovered.review));
  const reviewBytes = await readFile(discoveryOptions.output, 'utf8'); requests.length = 0;
  const options = parseOptions(['--steam-id', '76561198000000001', '--review-candidates', discoveryOptions.output, '--state-dir', path.join(dir, 'fetch'), '--output', path.join(dir, 'candidate.json'), '--report', path.join(dir, 'refresh-report.json')]);
  const refreshed = await runRefresh(options, dependencies);
  const directIds = requests.filter(r => r.url.includes('/game/')).map(r => Number(r.url.split('/').at(-1)));
  assert.deepEqual(directIds, [100, 300, 500, 600]); assert.deepEqual(unexpected, []);
  assert.deepEqual(refreshed.report.addedMappings, [[40, 100], [50, 300], [90, 500], [100, 600]]);
  assert.ok(base.mappings.every(([app, id]) => refreshed.candidate.mappings.some(([a, h]) => a === app && h === id)));
  assert.deepEqual(refreshed.candidate.lengths, [[100, 200, null, null, null], [200, 50, null, null, null], [300, 300, null, null, null]]);
  assert.deepEqual(refreshed.report.noLengths, [500]); assert.deepEqual(refreshed.report.failures, [{ hltbId: 600, category: 'identity-mismatch' }]);
  assert.ok(!refreshed.candidate.mappings.some(([a]) => [60, 70, 80].includes(a)));
  assert.deepEqual(refreshed.report.affectedApps.filter(a => !a.directlyTargeted).map(a => a.appId), [20]);
  const verified = verifyPair(baselineText, await readFile(options.output, 'utf8'), refreshed.report);
  assert.equal(verified.verified, true); assert.equal(verified.outcome, 'partial');
  const history = JSON.parse(await readFile(path.join(options.stateDir, 'history.json')));
  assert.deepEqual(Object.keys(history.entries), ['100', '300']);
  assert.equal(await readFile(discoveryOptions.output, 'utf8'), reviewBytes); assert.equal(await readFile(source, 'utf8'), baselineText);
  assert.ok(!JSON.stringify(refreshed.candidate).includes('999'));
  // A global conflict must block a fresh candidate even when that app is outside current scope.
  discovered.review.apps.push({ appId: 20, steamTitle: 'Conflict', outcome: 'candidates', category: null,
    candidates: [{ ...discovered.review.apps[0].candidates[0], hltbId: 9999, decision: 'approved' }] });
  await writeFile(discoveryOptions.output, JSON.stringify(discovered.review));
  options.output = path.join(dir, 'blocked.json'); requests.length = 0;
  const blocked = await runRefresh(options, dependencies); assert.equal(blocked.exitCode, 1);
  assert.ok(requests.every(r => !r.url.includes('/game/'))); await assert.rejects(readFile(options.output), { code: 'ENOENT' });
  assert.equal(await readFile(source, 'utf8'), baselineText);
});
