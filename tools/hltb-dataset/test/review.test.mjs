import test from 'node:test';
import assert from 'node:assert/strict';
import { parseReview, PREVIEW_WARNING, reviewSummary, approvedContribution } from '../review.mjs';
export function reviewFixture() {
  return { reviewVersion: 1, discoveryId: 'a'.repeat(32), baselineHash: 'b'.repeat(64), createdAt: 1700000000000,
    scopeHash: 'c'.repeat(64), libraries: { requested: 1, readable: 1, unavailable: 0, empty: 0 },
    requests: { attempts: 1, retries: 0, elapsedMs: 0 }, warning: PREVIEW_WARNING,
    apps: [{ appId: 10, steamTitle: 'Portal', outcome: 'candidates', category: null,
      candidates: [{ hltbId: 7231, hltbTitle: 'Portal', previewMinutes: [60, null, 90, null], source: 'primary', query: 'Portal', score: 1, decision: 'pending' }] }] };
}
test('review decisions are independent of search outcome; previews never enter contributions', () => {
  for (const decision of ['pending', 'approved', 'rejected']) {
    const r = reviewFixture(); r.apps[0].candidates[0].decision = decision;
    const parsed = parseReview(JSON.stringify(r));
    assert.equal(reviewSummary(parsed)[`${decision}PairCount`], 1);
    assert.deepEqual(approvedContribution(parsed).lengths, []);
    assert.equal(approvedContribution(parsed).mappings.length, decision === 'approved' ? 1 : 0);
  }
});
test('review rejects malformed, duplicate, absent and ambiguous decisions and invalid identities/previews', () => {
  const mutations = [r => r.reviewVersion++, r => r.apps.push(r.apps[0]), r => r.apps[0].candidates.push(r.apps[0].candidates[0]),
    r => delete r.apps[0].outcome, r => r.apps[0].outcome = 'done', r => delete r.apps[0].candidates[0].decision,
    r => r.apps[0].candidates[0].decision = 'accepted', r => r.apps[0].appId = 0, r => r.apps[0].candidates[0].hltbId = -1,
    r => r.apps[0].candidates[0].previewMinutes = [600001, null, null, null], r => r.apps[0].candidates[0].previewMinutes[0] = 1.5,
    r => { r.apps[0].candidates[0].decision = 'approved'; r.apps[0].candidates.push({ ...r.apps[0].candidates[0], hltbId: 2 }); },
    r => r.token = 'do not persist', r => r.apps[0].steamTitle = 'token=private'];
  for (const mutate of mutations) { const r = reviewFixture(); mutate(r); assert.throws(() => parseReview(JSON.stringify(r)), /invalid-review/); }
});
