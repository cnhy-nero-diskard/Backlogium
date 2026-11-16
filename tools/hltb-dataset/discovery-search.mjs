import { fail } from './refresh-options.mjs';
import { publicTitle } from './refresh-targets.mjs';
import { searchCategory } from './review.mjs';
export const ORIGIN = 'https://howlongtobeat.com';
export const FAST_ENDPOINT = '/api/search/site';
// Match ScrapingHltbDataSource.browserHeaders across the whole session, not just
// init: upstream sessions can be bound to the requesting client identity.
export const DISCOVERY_HEADERS = Object.freeze({
  'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
  Origin: ORIGIN, Referer: ORIGIN + '/', Accept: '*/*',
});
export function safeEndpoint(p) {
  return typeof p === 'string' && /^\/api\/[A-Za-z0-9_-]+(?:\/[A-Za-z0-9_-]+)*$/.test(p) && p.length < 200;
}
export function chunkPaths(html) {
  // Extract whole quoted references; never accept a safe-looking substring of an external URL.
  return [...new Set([...html.matchAll(/["']([^"']+)["']/g)].map((m) => m[1]).filter((p) =>
    /^\/_next\/static\/chunks\/[A-Za-z0-9_-]+(?:\/[A-Za-z0-9_-]+)*\.js$/.test(p) && p.length < 300))];
}
export function endpointPaths(js) {
  const init = [...js.matchAll(/["'`]([^"'`]+)\/init\?t=/g)].map((m) => m[1]);
  const posts = [...js.matchAll(/fetch\(\s*["']([^"']+)["']\s*,\s*\{[^}]*method\s*:\s*["']POST["']/g)].map((m) => m[1]);
  return [...new Set([...init, ...posts].filter(safeEndpoint))];
}
export function parseInit(text) {
  let r; try { r = JSON.parse(text); } catch { fail('invalid-session'); }
  const header = (s) => typeof s === 'string' && /^[\x21-\x7e]{1,512}$/.test(s);
  if (!header(r?.token) || ((r.hpKey != null || r.hpVal != null) &&
    (!header(r.hpKey) || !/^[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(r.hpKey) ||
      ['__proto__', 'constructor', 'prototype', 'searchType', 'searchTerms', 'searchPage', 'size', 'searchOptions', 'useCache'].includes(r.hpKey) || !header(r.hpVal)))) fail('invalid-session');
  return { token: r.token, hpKey: r.hpKey ?? null, hpVal: r.hpVal ?? null };
}
export function deduplicate(candidates) {
  const byId = new Map();
  for (const c of candidates) {
    const old = byId.get(c.hltbId);
    const richness = (v) => v.previewMinutes.filter((m) => m !== null).length;
    if (!old || richness(c) > richness(old) || (richness(c) === richness(old) && (c.score ?? 0) > (old.score ?? 0))) byId.set(c.hltbId, c);
  }
  return [...byId.values()];
}
export function parseSearch(text, privateStrings = []) {
  let r; try { r = JSON.parse(text); } catch { fail('malformed-search'); }
  if (!Array.isArray(r?.data) || r.data.length > 20) fail('malformed-search');
  return deduplicate(r.data.map((g) => {
    if (!Number.isSafeInteger(g?.game_id) || g.game_id <= 0 || typeof g.game_name !== 'string') fail('malformed-search');
    const previewMinutes = ['comp_main', 'comp_plus', 'comp_100', 'comp_all'].map((k) => {
      const s = g[k] ?? 0;
      if (!Number.isSafeInteger(s) || s < 0 || Math.floor((s + 30) / 60) > 600000) fail('malformed-search');
      return s <= 0 ? null : Math.floor((s + 30) / 60);
    });
    return { hltbId: g.game_id, hltbTitle: publicTitle(g.game_name, privateStrings), previewMinutes };
  }));
}

// Observable parity: Android HltbMatcher.scored/scoredBroader and HltbQueryGenerator.
// No classifier/threshold is ported. Tie order: score descending, then numeric ID ascending.
// Images are deliberately absent; richness uses only validated length previews, first tie wins.
export const normalize = (s) => s.toLowerCase().replace(/[™®©]/g, '').replace(/[^a-z0-9]+/g, ' ').trim().replace(/\s+/g, ' ');
export function similarity(a, b) {
  a = normalize(a); b = normalize(b);
  if (!a || !b) return 0;
  let prev = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    const row = [i];
    for (let j = 1; j <= b.length; j++) row[j] = Math.min(row[j - 1] + 1, prev[j] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    prev = row;
  }
  return 1 - prev[b.length] / Math.max(a.length, b.length);
}
const romans = ['I', 'II', 'III', 'IV', 'V', 'VI', 'VII', 'VIII', 'IX', 'X', 'XI', 'XII', 'XIII', 'XIV', 'XV'];
const editionTerms = ['game of the year edition', 'game of the year', 'definitive edition', 'enhanced edition', 'complete edition',
  'deluxe edition', 'ultimate edition', 'premium edition', 'collectors edition', "collector's edition", 'special edition', 'extended edition', 'remastered', 'goty', 'steam edition', 'windows edition'];
export function removeEdition(title) {
  let clean = title.replace(/[™®©]/g, '').replace(/\s*[\[(][^\])]*[\])]\s*/g, ' ');
  for (const term of editionTerms) clean = clean.replace(new RegExp(`\\b${term}\\b`, 'ig'), ' ');
  return clean.replace(/\s+/g, ' ').trim();
}
function subtitle(title) {
  for (const sep of [/\s*:\s*/, /\s*—\s*/, /\s*–\s*/, /\s+-\s+/]) {
    const match = sep.exec(title);
    if (match) { const core = title.slice(0, match.index).trim(); if (core && title.slice(match.index + match[0].length).trim()) return core; }
  }
  return title;
}
function numeralAlternative(title) {
  const a = /^(.*\D)(\d+)\s*$/.exec(title.trim());
  if (a) return romans[Number(a[2]) - 1] ? a[1] + romans[Number(a[2]) - 1] : '';
  const r = /^(.*\s)([ivxlcdm]+)\s*$/i.exec(title.trim());
  return r && r[1].trim() && romans.includes(r[2].toUpperCase()) ? r[1] + (romans.indexOf(r[2].toUpperCase()) + 1) : '';
}
export function variants(original) {
  const primary = normalize(original); if (!primary) return [];
  return [...new Set([removeEdition(original), subtitle(original), subtitle(removeEdition(original)),
    original.trim().replace(/^(the|a|an)\s+/i, ''), numeralAlternative(original), numeralAlternative(subtitle(original))]
    .map(normalize).filter((s) => s && s !== primary))].slice(0, 3);
}
const tokens = (s) => new Set(normalize(s).split(' ').filter((t) => t.length > 1));
const number = (s) => { const term = normalize(s).split(' ').at(-1); return /^\d+$/.test(term) ? Number(term) : romans.indexOf(term.toUpperCase()) >= 0 ? romans.indexOf(term.toUpperCase()) + 1 : null; };
export function scoreCandidate(original, name, broader = false) {
  if (!name) return 0;
  let score = similarity(original, name); if (!broader) return score;
  const a = tokens(original), b = tokens(name), union = new Set([...a, ...b]);
  const overlap = union.size ? [...a].filter((t) => b.has(t)).length / union.size : 0;
  score = score * .6 + overlap * .3;
  const core = normalize(original).replace(/\d+\s*$/, '').trim();
  if (core && normalize(name).includes(core)) score += .05;
  const edition = (s) => ['enhanced edition', 'definitive edition', 'game of the year', 'goty', 'remastered', 'complete edition', 'deluxe edition', 'ultimate edition'].some((e) => normalize(s).includes(e));
  if (edition(name) && !edition(original)) score -= .03;
  if (number(original) !== null && number(name) !== null && number(original) !== number(name)) score -= .35;
  return Math.max(0, Math.min(1, score));
}
export function rank(original, candidates, broader = false) {
  return deduplicate(candidates.map((c) => ({ ...c, score: scoreCandidate(original, c.hltbTitle, broader), decision: 'pending' })))
    .sort((a, b) => b.score - a.score || a.hltbId - b.hltbId);
}

export class SearchSession {
  constructor(budget, privateStrings = []) {
    this.budget = budget; this.privateStrings = privateStrings; this.session = null;
    this.failure = null; this.phase = 'session-init';
  }
  async init(endpoint) {
    this.phase = 'session-init';
    if (!safeEndpoint(endpoint)) fail('endpoint-absent');
    const init = parseInit(await this.budget.request(`${ORIGIN}${endpoint}/init?t=${this.budget.now()}`, 'hltb', { headers: DISCOVERY_HEADERS }));
    return { ...init, endpoint, expiresAt: this.budget.now() + 300000 };
  }
  async resolve() {
    try { return await this.init(FAST_ENDPOINT); } catch (e) { if (e.category !== 'not-found') throw e; }
    this.phase = 'endpoint-discovery';
    const homepage = await this.budget.request(`${ORIGIN}/`, 'hltb', { headers: DISCOVERY_HEADERS });
    const attempted = new Set([FAST_ENDPOINT]);
    for (const chunk of chunkPaths(homepage).slice(0, 12)) {
      this.phase = 'endpoint-discovery';
      let js;
      try { js = await this.budget.request(`${ORIGIN}${chunk}`, 'hltb', { headers: DISCOVERY_HEADERS }); }
      catch (e) { if (e.category === 'not-found') continue; throw e; }
      for (const endpoint of endpointPaths(js)) {
        this.phase = 'endpoint-discovery';
        if (attempted.has(endpoint)) continue;
        if (attempted.size >= 4) fail('endpoint-absent');
        attempted.add(endpoint);
        try { return await this.init(endpoint); } catch (e) { if (e.category !== 'not-found') throw e; }
      }
    }
    this.phase = 'endpoint-discovery';
    fail('endpoint-absent');
  }
  async ready() {
    if (this.failure) fail(this.failure.category, this.failure.status);
    if (this.budget.stopped) fail(this.budget.stopped);
    if (this.session && this.session.expiresAt > this.budget.now()) return;
    try { this.session = await this.resolve(); }
    catch (e) {
      const status = Number.isInteger(e.status) && e.status >= 100 && e.status <= 599 ? e.status : null;
      this.failure = { phase: this.phase, category: searchCategory(e.category), status };
      // A shared setup failure is terminal after transport's bounded retries,
      // including malformed init: do not repeat it for every remaining title.
      this.budget.stopped ??= this.failure.category;
      throw e;
    }
  }
  async search(query) {
    if (!publicTitle(query, this.privateStrings)) fail('unsearchable-title');
    await this.ready();
    const { token, hpKey, hpVal, endpoint } = this.session;
    const payload = { searchType: 'games', searchTerms: query.trim().split(/\s+/), searchPage: 1, size: 20,
      searchOptions: { games: { userId: 0, platform: '', sortCategory: 'popular', rangeCategory: 'main', rangeTime: { min: null, max: null },
        gameplay: { perspective: '', flow: '', genre: '', difficulty: '' }, rangeYear: { min: '', max: '' }, modifier: '' },
      users: { sortCategory: 'postcount' }, lists: { sortCategory: 'follows' }, filter: '', sort: 0, randomizer: 0 }, useCache: true };
    const headers = { ...DISCOVERY_HEADERS, Accept: 'application/json', 'Content-Type': 'application/json', 'x-auth-token': token };
    if (hpKey) { payload[hpKey] = hpVal; headers['x-hp-key'] = hpKey; headers['x-hp-val'] = hpVal; }
    return parseSearch(await this.budget.request(`${ORIGIN}${endpoint}`, 'hltb', { method: 'POST', headers, body: JSON.stringify(payload) }), this.privateStrings);
  }
}
