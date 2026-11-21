import test from 'node:test';
import assert from 'node:assert/strict';
import { parseSearch, parseInit, chunkPaths, endpointPaths, safeEndpoint, variants, rank, scoreCandidate, similarity } from '../discovery-search.mjs';
// Behavior sources: Android HltbBundleParserTest (hashed chunks, nested init preference,
// Portal 2 seconds rounding), HltbMatcherTest and HltbQueryGeneratorTest (no classifier).
test('search parses empty and valid payloads, rounds seconds and keeps richest duplicate', () => {
  assert.deepEqual(parseSearch('{"data":[]}'), []);
  const games = [{ game_id: 7231, game_name: 'Portal 2', comp_main: 30730, comp_plus: 49433, comp_100: 81179, comp_all: 0 },
    { game_id: 7231, game_name: 'Portal 2' }, { game_id: 1, game_name: 'x', comp_main: 90 }];
  const result = parseSearch(JSON.stringify({ data: games }));
  assert.deepEqual(result[0].previewMinutes, [512, 824, 1353, null]); assert.equal(result[1].previewMinutes[0], 2);
  for (const body of ['{}', 'null', '{', '{"data":[{}]}', '{"data":[{"game_id":0,"game_name":"x"}]}',
    '{"data":[{"game_id":1,"game_name":"x","comp_main":36000060}]}']) assert.throws(() => parseSearch(body), /malformed-search/);
});
test('init and paths are parsed as data with same-origin strict routes', () => {
  assert.equal(parseInit('{"token":"fixture","hpKey":"key","hpVal":"value"}').hpKey, 'key');
  for (const body of ['{}', 'null', '{', '{"token":"x","hpKey":"searchType","hpVal":"games"}', '{"token":"x\\n"}']) assert.throws(() => parseInit(body), /invalid-session/);
  assert.deepEqual(chunkPaths('<script src="/_next/static/chunks/19cd32hawdh7y.js"></script><script src="/_next/static/chunks/19cd32hawdh7y.js"></script>'), ['/_next/static/chunks/19cd32hawdh7y.js']);
  assert.deepEqual(endpointPaths('fetch(`/api/search/site/init?t=${Date.now()}`); fetch("/api/error",{method:"POST"})'), ['/api/search/site', '/api/error']);
  for (const p of ['//evil/api/a', 'https://evil/api/a', '/api/../a', '/api/a?x=1', '/api/a#x', '/api/%2f', '/api/a:80']) assert.equal(safeEndpoint(p), false);
  assert.deepEqual(chunkPaths('"https://evil/_next/static/chunks/a.js" "/_next/static/chunks/../a.js" "/_next/static/chunks/a.js?x"'), []);
  globalThis.searchParserExecuted = false;
  endpointPaths('globalThis.searchParserExecuted = true;'); assert.equal(globalThis.searchParserExecuted, false);
});
test('candidate-only primary scoring stays pending and has deterministic ties', () => {
  assert.equal(similarity('Portal™ 2', 'portal 2'), 1);
  const r = rank('Portal 2', [2, 1].map(hltbId => ({ hltbId, hltbTitle: 'Portal 2', previewMinutes: [null, null, null, null] })));
  assert.deepEqual(r.map(c => c.hltbId), [1, 2]); assert.ok(r.every(c => c.decision === 'pending' && c.score === 1));
});
test('broader variants preserve core/sequel, cap at three, remove duplicates and score original', () => {
  assert.deepEqual(variants('Portal 2'), ['portal ii']); assert.deepEqual(variants('Final Fantasy VII'), ['final fantasy 7']);
  assert.deepEqual(variants('™!!!'), []); assert.deepEqual(variants('Portal'), []);
  assert.ok(variants('The Game 2: Subtitle Deluxe Edition (Steam)').length <= 3);
  assert.ok(variants('Game 2: Subtitle').includes('game 2'));
  assert.ok(variants('Game Definitive Edition').includes('game'));
  assert.ok(scoreCandidate('Final Fantasy VII', 'Final Fantasy VIII', true) < scoreCandidate('Final Fantasy VII', 'Final Fantasy 7', true));
  assert.ok(scoreCandidate('Game 2', 'Game 3', true) < scoreCandidate('Game 2', 'Game II', true));
  const c = { hltbId: 1, hltbTitle: 'Game 2', previewMinutes: [1, null, null, null], query: 'game 2' };
  assert.equal(rank('Game 2: Subtitle', [c], true)[0].score, scoreCandidate('Game 2: Subtitle', 'Game 2', true));
});
