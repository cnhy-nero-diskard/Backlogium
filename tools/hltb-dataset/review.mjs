import { fail } from './refresh-options.mjs';
import { publicTitle } from './refresh-targets.mjs';

export const PREVIEW_WARNING = 'Search previews and discovery times are not direct-page observations or successful-fetch times.';
const outcomes = new Set(['candidates', 'unmatched', 'failed', 'unattempted']);
const decisions = ['pending', 'approved', 'rejected'];
export const SEARCH_FAILURES = new Set(['unsearchable-title', 'malformed-search', 'invalid-session', 'endpoint-absent',
  'access-denied', 'challenge-response', 'response-too-large', 'transport-failed', 'rate-limited', 'server-failed', 'http-failed', 'budget-exhausted', 'search-failed']);
export const searchCategory = (s) => SEARCH_FAILURES.has(s) ? s : 'search-failed';
const id = (n) => Number.isSafeInteger(n) && n > 0;
const digest = (s) => typeof s === 'string' && /^[a-f0-9]{64}$/.test(s);
const exact = (obj, fields) => obj && typeof obj === 'object' && !Array.isArray(obj) &&
  Object.keys(obj).length === fields.length && fields.every((k) => Object.hasOwn(obj, k));
const title = (s) => s === null || (typeof s === 'string' && publicTitle(s) === s);
export function parseReview(text) {
  let r; try { r = JSON.parse(text); } catch { fail('invalid-review'); }
  if (!exact(r, ['reviewVersion', 'discoveryId', 'baselineHash', 'createdAt', 'scopeHash', 'libraries', 'requests', 'warning', 'apps']) ||
    r.reviewVersion !== 1 || !/^[a-f0-9]{32}$/.test(r.discoveryId) || !digest(r.baselineHash) || !digest(r.scopeHash) ||
    !Number.isSafeInteger(r.createdAt) || r.createdAt <= 0 || r.warning !== PREVIEW_WARNING || !Array.isArray(r.apps)) fail('invalid-review');
  for (const [obj, fields] of [[r.libraries, ['requested', 'readable', 'unavailable', 'empty']], [r.requests, ['attempts', 'retries', 'elapsedMs']]]) {
    if (!exact(obj, fields) || fields.some((f) => !Number.isSafeInteger(obj[f]) || obj[f] < 0)) fail('invalid-review');
  }
  if (r.libraries.requested !== r.libraries.readable + r.libraries.unavailable || r.libraries.empty > r.libraries.readable) fail('invalid-review');
  const apps = new Set();
  for (const a of r.apps) {
    if (!exact(a, ['appId', 'steamTitle', 'outcome', 'category', 'candidates']) || !id(a.appId) || apps.has(a.appId) ||
      !title(a.steamTitle) || !outcomes.has(a.outcome) || !Array.isArray(a.candidates)) fail('invalid-review');
    apps.add(a.appId);
    if ((a.outcome === 'candidates') !== (a.candidates.length > 0) ||
      (['failed', 'unattempted'].includes(a.outcome) ? !SEARCH_FAILURES.has(a.category) : a.outcome === 'candidates' ? a.category !== null && !SEARCH_FAILURES.has(a.category) : a.category !== null)) fail('invalid-review');
    const seen = new Set(); let approvals = 0;
    for (const c of a.candidates) {
      if (!exact(c, ['hltbId', 'hltbTitle', 'previewMinutes', 'source', 'query', 'score', 'decision']) ||
        !id(c.hltbId) || seen.has(c.hltbId) || !title(c.hltbTitle) || !title(c.query) || !c.query ||
        !['primary', 'broader'].includes(c.source) || !Number.isFinite(c.score) || c.score < 0 || c.score > 1 ||
        !decisions.includes(c.decision) || !Array.isArray(c.previewMinutes) || c.previewMinutes.length !== 4 ||
        c.previewMinutes.some((n) => n !== null && (!Number.isSafeInteger(n) || n < 0 || n > 600000))) fail('invalid-review');
      seen.add(c.hltbId); if (c.decision === 'approved') approvals++;
    }
    if (approvals > 1) fail('invalid-review');
  }
  return r;
}
export function approvedContribution(review) {
  return { schemaVersion: 1, datasetVersion: 0, gatheredAt: review.createdAt, lengths: [],
    mappings: review.apps.flatMap((a) => a.candidates.filter((c) => c.decision === 'approved').map((c) => [a.appId, c.hltbId])) };
}
export function reviewSummary(review, currentApps = review.apps) {
  const scope = new Set(currentApps.map((a) => a.appId));
  const pairs = review.apps.flatMap((a) => a.candidates.map((c) => ({ appId: a.appId, hltbId: c.hltbId, decision: c.decision })));
  const result = { artifactVersion: 1, candidateApps: review.apps.filter((a) => a.candidates.length).length,
    candidatePairs: pairs.length, partialSearchFailures: review.apps.filter((a) => a.outcome === 'candidates' && a.category !== null).map((a) => ({ appId: a.appId, category: a.category })), unmatchedApps: review.apps.filter((a) => a.outcome === 'unmatched').map((a) => a.appId),
    failedApps: review.apps.filter((a) => a.outcome === 'failed').map((a) => ({ appId: a.appId, category: a.category })),
    unattemptedApps: review.apps.filter((a) => a.outcome === 'unattempted').map((a) => a.appId) };
  for (const d of decisions) {
    result[`${d}Pairs`] = pairs.filter((p) => p.decision === d).map(({ appId, hltbId }) => ({ appId, hltbId }));
    result[`${d}PairCount`] = result[`${d}Pairs`].length;
  }
  result.excludedPairs = result.approvedPairs.filter((p) => !scope.has(p.appId));
  result.excludedPairCount = result.excludedPairs.length;
  return result;
}
