import { hash } from "./refresh-storage.mjs";
import { publicTitle } from "./refresh-targets.mjs";

export const CATEGORIES = new Set(["access-denied", "challenge-response", "not-found", "unrecognized-page", "malformed-page",
  "unrecognized-units", "identity-mismatch", "invalid-length", "ambiguous-page", "response-too-large",
  "transport-failed", "rate-limited", "server-failed", "http-failed", "budget-exhausted", "fetch-failed"]);
export const safeCategory = (value) => CATEGORIES.has(value) ? value : "fetch-failed";
const emptyStyles = () => [null, null, null, null];
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
export function buildReport({ options, base, baselineText, candidate, candidateText, plan, observations = {}, stats, runId = null, privateStrings = [] }) {
  const oldMappings = new Map(base.mappings);
  const oldLengths = new Map(base.lengths.map((r) => [r[0], r.slice(1)]));
  const newLengths = new Map((candidate ?? base).lengths.map((r) => [r[0], r.slice(1)]));
  const targets = new Set(plan.apps.map((a) => a.appId));
  const titles = new Map(plan.apps.map((a) => [a.appId, publicTitle(a.title, privateStrings)]));
  const changedLengthIds = [...newLengths.keys()].filter((id) => !same(oldLengths.get(id), newLengths.get(id)));
  const changedIds = new Set(changedLengthIds);
  const affectedApps = (candidate?.mappings ?? []).filter(([app, id]) => !oldMappings.has(app) || changedIds.has(id)).map(([appId, hltbId]) => ({
    appId, hltbId, title: titles.get(appId) ?? publicTitle(observations[hltbId]?.title, privateStrings),
    addedMapping: !oldMappings.has(appId), directlyTargeted: targets.has(appId),
    old: oldLengths.get(oldMappings.get(appId)) ?? emptyStyles(), new: newLengths.get(hltbId) ?? emptyStyles(),
  }));
  const failures = []; const noLengths = []; const unattempted = []; const usable = []; const unchanged = [];
  for (const id of plan.selected) {
    const o = observations[id];
    if (!o) unattempted.push(id);
    else if (o.status === "failed") failures.push({ hltbId: id, category: safeCategory(o.category) });
    else if (o.status === "no-lengths") noLengths.push(id);
    else if (o.status === "usable") {
      usable.push(id); if (same(oldLengths.get(id), o.lengths)) unchanged.push(id);
    } else unattempted.push(id);
  }
  const blocked = plan.libraries.readable === 0 || plan.conflicts.length > 0;
  const partial = plan.libraries.unavailable > 0 || plan.unmapped.length > 0 || failures.length > 0 || noLengths.length > 0 || (!options.dryRun && unattempted.length > 0) || stats.exhausted;
  const changed = candidateText !== undefined && candidateText !== baselineText;
  const affected = new Set(affectedApps.map((a) => a.appId));
  return {
    toolVersion: 1, reportVersion: 1, runId, mode: options.mode,
    outcome: blocked ? "blocked" : options.dryRun ? "plan-only" : partial ? "partial" : "complete",
    partialCoverage: partial, changed,
    baseline: { sha256: hash(baselineText), datasetVersion: base.datasetVersion, gatheredAt: base.gatheredAt },
    candidate: candidate ? { sha256: hash(candidateText), datasetVersion: candidate.datasetVersion, gatheredAt: candidate.gatheredAt } : null,
    libraries: { requested: plan.libraries.requested, readable: plan.libraries.readable, unavailable: plan.libraries.unavailable, empty: plan.libraries.empty },
    scope: { targetedApps: plan.apps.length, uniqueHltbIds: plan.uniqueHltbIds, selectedHltbIds: plan.selected.length, modeExcluded: plan.modeExcluded },
    plannedHltbIds: [...plan.selected],
    addedMappings: (candidate?.mappings ?? []).filter(([app]) => !oldMappings.has(app)),
    changedLengthIds, affectedApps,
    checked: { usableEntries: usable.length, unchangedEntries: unchanged.length,
      unchangedTargetedApps: plan.apps.filter((a) => unchanged.includes(plan.mappings.find(([app]) => app === a.appId)?.[1])).length },
    untouched: { canonicalApps: base.mappings.filter(([app]) => !affected.has(app)).length,
      canonicalLengthEntries: base.lengths.filter(([id]) => !changedIds.has(id)).length },
    unmapped: plan.unmapped.map((a) => ({ appId: a.appId, title: publicTitle(a.title, privateStrings) })),
    mappingConflicts: plan.conflicts.map((c) => ({ appId: c.appId, existingHltbId: c.existingHltbId, reviewedHltbId: c.reviewedHltbId })),
    noLengths, failures, unattempted: options.dryRun ? [] : unattempted,
    requests: { attempts: stats.requests, retries: stats.retries, backoffMs: stats.backoffMs, elapsedMs: stats.elapsedMs, budgetExhausted: stats.exhausted,
      maxRequests: options.maxRequests, maxRunSeconds: options.maxRunSeconds },
    limitations: { globalGatheredAt: "Schema-v1 gatheredAt is global merge metadata; it does not establish freshness of failed or untouched entries.",
      ownership: "Public mappings and game-specific gaps disclose selected ownership scope without account attribution." },
  };
}
