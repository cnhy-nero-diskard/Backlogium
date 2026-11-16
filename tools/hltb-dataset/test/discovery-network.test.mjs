import test from 'node:test';
import assert from 'node:assert/strict';
import { RequestBudget } from '../refresh-network.mjs';
import { SearchSession, DISCOVERY_HEADERS } from '../discovery-search.mjs';
function fixture(fetch, options = {}) {
  let time = 1700000000000; const calls = [];
  const budget = new RequestBudget({ maxRequests: 500, maxRunSeconds: 900, ...options }, {
    now: () => time, sleep: async ms => { time += ms; }, random: () => 0,
    fetch: async (url, opts) => { calls.push({ url, opts, time }); return fetch(url, opts, calls.length); } });
  return { budget, session: new SearchSession(budget), calls };
}
test('search shares spacing and session, sends controlled POST handshake, keeps tokens in memory', async () => {
  const f = fixture((url) => new Response(url.includes('/init?') ? '{"token":"fixture-token","hpKey":"fixtureKey","hpVal":"fixtureValue"}' : '{"data":[]}'));
  await f.session.search('Portal 2'); await f.session.search('Game');
  assert.equal(f.calls.length, 3);
  assert.equal(DISCOVERY_HEADERS['User-Agent'], 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36');
  for (const { opts } of f.calls) {
    assert.equal(opts.headers['User-Agent'], DISCOVERY_HEADERS['User-Agent']);
    assert.equal(opts.headers.Origin, 'https://howlongtobeat.com');
    assert.equal(opts.headers.Referer, 'https://howlongtobeat.com/');
    assert.equal(opts.headers.Accept, opts.method === 'POST' ? 'application/json' : '*/*');
  }
  assert.ok(!Object.hasOwn(f.calls[0].opts.headers, 'x-auth-token'));
  const post = f.calls[1].opts; assert.equal(post.method, 'POST'); assert.equal(post.redirect, 'error');
  assert.equal(post.headers['x-auth-token'], 'fixture-token');
  assert.equal(JSON.parse(post.body).fixtureKey, 'fixtureValue'); assert.equal(JSON.parse(post.body).size, 20);
  assert.deepEqual(JSON.parse(post.body).searchTerms, ['Portal', '2']);
  assert.ok(f.calls.slice(1).every((c, i) => c.time - f.calls[i].time >= 1000));
  assert.ok(!JSON.stringify(f.budget.snapshot()).includes('fixture-token'));
});
test('rotation only follows 404, same-origin routes and finite chunk/endpoint attempts', async () => {
  const f = fixture(url => new Response(url.includes('/init?') ? (url.includes('/rotated/') ? '{"token":"ok"}' : 'absent') :
    url.endsWith('.js') ? 'fetch(`/api/rotated/init?t=${Date.now()}`)' : url.endsWith('/') ? '<script src="/_next/static/chunks/a.js"></script>' : '{"data":[]}',
  { status: url.includes('/search/site/init?') ? 404 : 200 }));
  await f.session.search('Game'); assert.equal(f.calls.length, 5);
  assert.ok(f.calls.every(c => new URL(c.url).origin === 'https://howlongtobeat.com'));
  assert.ok(f.calls.every(c => c.opts.headers['User-Agent'] === DISCOVERY_HEADERS['User-Agent'] && c.opts.headers.Origin === DISCOVERY_HEADERS.Origin && c.opts.headers.Referer === DISCOVERY_HEADERS.Referer));
  const limit = fixture(url => new Response(url.includes('/init?') ? 'absent' : url.endsWith('/') ? Array.from({length: 30}, (_, i) => `"/_next/static/chunks/${i}.js"`).join(' ') : '', { status: url.includes('/init?') ? 404 : 200 }));
  await assert.rejects(limit.session.search('Game'), /endpoint-absent/); assert.equal(limit.calls.length, 14);
  const endpoints = fixture(url => new Response(url.includes('/init?') ? 'absent' : url.endsWith('/') ? '"/_next/static/chunks/a.js"' : Array.from({length: 20}, (_, i) => `fetch(\`/api/path${i}/init?t=1\`)`).join(';'), { status: url.includes('/init?') ? 404 : 200 }));
  await assert.rejects(endpoints.session.search('Game'), /endpoint-absent/); assert.equal(endpoints.calls.filter(c => c.url.includes('/init?')).length, 4);
});
test('denial/challenge at init, homepage, chunk, alternate init or POST stops all later work', async () => {
  for (let stage = 1; stage <= 5; stage++) for (const challenge of [false, true]) {
    const f = fixture((url, opts, n) => {
      if (n === stage) return new Response(challenge ? '<html>Just a moment cf-chl-</html>' : 'denied', { status: challenge ? 200 : 403 });
      if (n === 1) return new Response('absent', { status: 404 });
      if (n === 2) return new Response('"/_next/static/chunks/a.js"');
      if (n === 3) return new Response('fetch(`/api/other/init?t=1`)');
      return new Response('{"token":"ok"}');
    });
    await assert.rejects(f.session.search('Game'), challenge ? /challenge-response/ : /access-denied/);
    await assert.rejects(f.session.search('Later'), challenge ? /challenge-response/ : /access-denied/);
    assert.equal(f.calls.length, stage);
  }
});
test('discovery header overrides do not change Steam or direct-page refresh defaults', async () => {
  const f = fixture(() => new Response('{}'));
  await f.budget.request('https://api.steampowered.com/fixture', 'steam');
  await f.budget.request('https://howlongtobeat.com/game/1', 'hltb');
  assert.deepEqual(f.calls.map(c => c.opts.headers), [
    { Accept: 'application/json', 'User-Agent': 'Backlogium-HLTB-Refresh/1' },
    { Accept: 'text/html', 'User-Agent': 'Backlogium-HLTB-Refresh/1' },
  ]);
});
test('malformed/transient init does not rediscover; total budgets and Retry-After stop requests', async () => {
  for (const status of [200, 429, 500]) {
    const f = fixture(() => new Response('{}', { status }));
    await assert.rejects(f.session.search('Game'));
    assert.equal(f.calls.length, status === 200 ? 1 : 3); assert.ok(f.calls.every(c => c.url.includes('/init?')));
  }
  const f = fixture(() => new Response('', { status: 429, headers: { 'Retry-After': '100' } }), { maxRunSeconds: 10 });
  await assert.rejects(f.session.search('Game'), /budget-exhausted/); assert.equal(f.calls.length, 1);
  const max = fixture(() => new Response('{"token":"ok"}'), { maxRequests: 1 });
  await assert.rejects(max.session.search('Game'), /budget-exhausted/); assert.equal(max.calls.length, 1);
  const large = fixture(() => new Response('x'.repeat(5 * 1024 * 1024 + 1)));
  await assert.rejects(large.session.search('Game'), /response-too-large/); assert.equal(large.calls.length, 1);
});
test('denied streams are cancelled without reading, retrying, or leaking raw details', async () => {
  for (const status of [401, 403]) for (const cleanup of ['reject', 'throw', 'stall']) {
    let cancelled = false, signal;
    const f = fixture((_url, opts) => {
      signal = opts.signal;
      return { status, body: {
        getReader: () => { throw Error('must not read denied body'); },
        cancel: () => {
          cancelled = true;
          if (cleanup === 'throw') throw Error('PRIVATE cleanup error');
          return cleanup === 'stall' ? new Promise(() => {}) : Promise.reject(Error('PRIVATE cleanup error'));
        },
      } };
    });
    await assert.rejects(f.session.search('Game'), error => {
      assert.equal(error.category, 'access-denied'); assert.equal(error.status, status);
      assert.ok(!JSON.stringify(error).includes('PRIVATE')); return true;
    });
    assert.ok(cancelled); assert.ok(signal.aborted);
    await assert.rejects(f.session.search('Later'), /access-denied/);
    assert.equal(f.calls.length, 1);
  }
});
test('HTTP failure metadata is fixed category and validated status, not response text', async () => {
  for (const [status, body, category] of [[200, 'captcha PRIVATE', 'challenge-response'], [404, 'PRIVATE', 'not-found'], [500, 'PRIVATE', 'server-failed']]) {
    const f = fixture(() => new Response(body, { status }));
    await assert.rejects(f.budget.request('https://howlongtobeat.com/fixture', 'hltb'), error => {
      assert.equal(error.category, category); assert.equal(error.status, status);
      assert.ok(!JSON.stringify(error).includes('PRIVATE')); return true;
    });
  }
});
