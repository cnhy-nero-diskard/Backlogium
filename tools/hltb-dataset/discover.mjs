#!/usr/bin/env node
import { randomBytes } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { fail, parseOptions, RefreshError, SOURCE, TOOL_DIR } from './refresh-options.mjs';
import { parseDataset, serializeDataset } from './merge.mjs';
import { createPrivateJson, fingerprint, guardPaths, guardReview, hash, writeJson } from './refresh-storage.mjs';
import { RequestBudget } from './refresh-network.mjs';
import { publicTitle, resolveLibraries } from './refresh-targets.mjs';
import { SearchSession, rank, variants } from './discovery-search.mjs';
import { parseReview, PREVIEW_WARNING, reviewSummary, searchCategory } from './review.mjs';
export const HELP = `Usage: node tools/hltb-dataset/discover.mjs --steam-id <SteamID64> [options]
  --steam-id <id>       Repeat for readable owned-library union
  --app-id <id>         Repeat to intersect scope; covered apps are excluded
  --dry-run            Steam reads only; no search/review/state/history writes
  --broader            At most three variants after a successful empty primary
  --output <file>       NEW review file beneath tools/hltb-dataset/.local/
  --report <file>       Explicit sanitized plan/execution/inspection report
  --max-requests <n>    Default 500 attempts across all phases, including retries
  --max-run-seconds <n> Default 900 seconds; serial spacing >=1 second
  --inspect <file>      Offline current decisions; only --report can accompany it
  --help               Show help
Default execution review/report: .local/discovery/<opaque-id>/{review,report}.json.
STEAM_WEB_API_KEY comes only from the environment. Inspection needs no credentials.
Exit 0 complete/clean plan, 2 partial (unmatched/failed/unattempted or library gaps),
1 invalid/blocked. Discovery never fetches direct game pages or writes dataset/history.
Session setup failure before any search POST is blocked (1), not a failed game;
after earlier POSTs it is partial (2). Reviews/inspection retain sessionFailure.
Every pair starts pending. Owned app IDs and decisions are private; publishing game
mappings/gaps reveals ownership. Fresh-run recovery uses a new output; no resume.
`;
export function parseDiscoveryOptions(args) {
  if (args.length === 1 && ['--help', '-h'].includes(args[0])) return { help: true };
  const inspectIndex = args.indexOf('--inspect');
  if (inspectIndex >= 0) {
    const options = { stateDir: path.join(TOOL_DIR, '.local') }; const seen = new Set();
    for (let i = 0; i < args.length; i++) {
      const f = args[i]; if (!['--inspect', '--report'].includes(f) || seen.has(f)) fail('inspect-option-conflict');
      seen.add(f); const v = args[++i]; if (!v || v.startsWith('--')) fail('missing-option-value');
      options[f === '--inspect' ? 'inspect' : 'report'] = path.resolve(v);
    }
    options.explicitReport = !!options.report; return options;
  }
  const allowed = new Set(['--steam-id', '--app-id', '--output', '--report', '--max-requests', '--max-run-seconds']);
  const filtered = []; let broader = false; const seen = new Set();
  for (let i = 0; i < args.length; i++) {
    const f = args[i];
    if (f === '--broader') { if (seen.has(f)) fail('duplicate-option'); seen.add(f); broader = true; }
    else if (f === '--dry-run') filtered.push(f);
    else if (allowed.has(f)) { filtered.push(f, args[++i]); }
    else fail('unknown-option');
  }
  const options = parseOptions(filtered); const discoveryId = randomBytes(16).toString('hex');
  const dir = path.join(TOOL_DIR, '.local', 'discovery', discoveryId);
  if (!filtered.includes('--output')) options.output = path.join(dir, 'review.json');
  if (!options.explicitReport) options.report = path.join(dir, 'report.json');
  return { ...options, broader, discoveryId };
}
function reportFor(review, outcome, budget, options) {
  return { discoveryReportVersion: 1, outcome, baselineHash: review.baselineHash,
    libraries: review.libraries, scope: { uncoveredApps: review.apps.length }, review: reviewSummary(review),
    requests: { attempts: budget.stats.requests, retries: budget.stats.retries, elapsedMs: budget.snapshot().elapsedMs,
      maxRequests: options.maxRequests, maxRunSeconds: options.maxRunSeconds, budgetExhausted: budget.stats.exhausted }, warning: PREVIEW_WARNING,
    ownership: 'Owned app IDs and review decisions are private; publishing mappings or game-specific gaps discloses ownership.' };
}
export async function inspectReview(options, dependencies = {}) {
  const source = dependencies.source ?? SOURCE;
  const unused = path.join(TOOL_DIR, '.local', 'inspection', randomBytes(16).toString('hex'), 'unused.json');
  await guardReview(options.inspect, { source, protectedFiles: options.report ? [options.report] : [] });
  if (options.report) await guardPaths({ ...options, reviewCandidates: options.inspect, output: unused }, source);
  const text = await readFile(options.inspect, 'utf8'); const review = parseReview(text);
  const report = { discoveryReportVersion: 1, outcome: 'inspection-only', artifactHash: hash(text), review: reviewSummary(review), warning: PREVIEW_WARNING };
  if (options.explicitReport) await writeJson(options.report, report);
  return { report, exitCode: 0 };
}
export async function runDiscovery(options, dependencies = {}) {
  if (options.inspect) return inspectReview(options, dependencies);
  const source = dependencies.source ?? SOURCE;
  await guardPaths(options, source);
  await guardReview(options.output, { source, protectedFiles: [options.report], output: true });
  const baselineText = await readFile(source, 'utf8'); let base;
  try { base = parseDataset(baselineText); } catch { fail('invalid-dataset-input'); }
  if (serializeDataset(base) !== baselineText) fail('noncanonical-source');
  const key = (dependencies.env ?? process.env).STEAM_WEB_API_KEY;
  if (typeof key !== 'string' || !key.trim()) fail('missing-steam-key');
  const privateStrings = [key, ...options.steamIds]; const budget = new RequestBudget(options, dependencies);
  const scope = await resolveLibraries(options, key, budget); const covered = new Set(base.mappings.map(([a]) => a));
  const apps = scope.apps.filter(a => !covered.has(a.appId));
  if (options.dryRun || !scope.libraries.readable) {
    const partial = scope.libraries.unavailable > 0;
    const report = { discoveryReportVersion: 1, outcome: scope.libraries.readable ? 'plan-only' : 'blocked',
      baselineHash: hash(baselineText), libraries: scope.libraries, scope: { uncoveredApps: apps.length },
      plannedApps: apps.map(a => ({ appId: a.appId, title: a.title })), broader: options.broader,
      requests: { attempts: budget.stats.requests, maxRequests: options.maxRequests, maxRunSeconds: options.maxRunSeconds }, warning: PREVIEW_WARNING };
    if (options.explicitReport) await writeJson(options.report, report);
    return { report, exitCode: !scope.libraries.readable ? 1 : partial ? 2 : 0 };
  }
  const review = { reviewVersion: 1, discoveryId: options.discoveryId, baselineHash: hash(baselineText), createdAt: budget.now(),
    scopeHash: fingerprint({ apps, broader: options.broader }), libraries: scope.libraries,
    requests: { attempts: 0, retries: 0, elapsedMs: 0 }, warning: PREVIEW_WARNING,
    apps: apps.map(a => ({ appId: a.appId, steamTitle: a.title, outcome: 'unattempted', category: 'budget-exhausted', candidates: [] })) };
  const session = new SearchSession(budget, privateStrings);
  for (const app of review.apps) {
    if (budget.stopped) { app.category = searchCategory(budget.stopped); continue; }
    if (!publicTitle(app.steamTitle, privateStrings)) { app.outcome = 'failed'; app.category = 'unsearchable-title'; continue; }
    const postsBefore = budget.postAttempts; app.category = null;
    try {
      await session.ready();
      const primary = await session.search(app.steamTitle);
      let candidates = primary.map(c => ({ ...c, source: 'primary', query: app.steamTitle }));
      if (!primary.length && options.broader) {
        for (const query of variants(app.steamTitle)) {
          try { candidates.push(...(await session.search(query)).map(c => ({ ...c, source: 'broader', query }))); }
          catch (e) { app.category = searchCategory(e.category); break; }
        }
      }
      app.candidates = rank(app.steamTitle, candidates, !primary.length);
      app.outcome = app.candidates.length ? 'candidates' : app.category ? 'failed' : 'unmatched';
      if (app.outcome === 'unmatched') app.category = null;
      if (budget.stopped && !app.candidates.length) { app.outcome = 'failed'; app.category = searchCategory(budget.stopped); }
    } catch (e) {
      app.outcome = postsBefore === budget.postAttempts ? 'unattempted' : 'failed';
      app.category = searchCategory(e.category);
    }
  }
  if (session.failure) review.sessionFailure = session.failure;
  review.requests = { attempts: budget.stats.requests, retries: budget.stats.retries, elapsedMs: Math.round(budget.snapshot().elapsedMs) };
  // Validate exactly what is persisted; never write upstream responses/session state.
  parseReview(JSON.stringify(review));
  await guardPaths(options, source); await guardReview(options.output, { source, protectedFiles: [options.report], output: true });
  await createPrivateJson(options.output, review);
  const partial = scope.libraries.unavailable > 0 || review.apps.some(a => a.outcome !== 'candidates' || a.category !== null);
  const blocked = !!session.failure && budget.postAttempts === 0;
  const report = reportFor(review, blocked ? 'blocked' : partial ? 'partial' : 'complete', budget, options);
  await writeJson(options.report, report);
  return { report, review, exitCode: blocked ? 1 : partial ? 2 : 0 };
}
export async function main(args = process.argv.slice(2), dependencies = {}) {
  try {
    const options = parseDiscoveryOptions(args);
    if (options.help) { (dependencies.stdout ?? console.log)(HELP); return 0; }
    const result = await runDiscovery(options, dependencies); (dependencies.stdout ?? console.log)(JSON.stringify(result.report, null, 2)); return result.exitCode;
  } catch (e) {
    (dependencies.stderr ?? console.error)(JSON.stringify({ discoveryReportVersion: 1, outcome: 'blocked', category: e instanceof RefreshError ? e.category : 'execution-failed' })); return 1;
  }
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) process.exitCode = await main();
