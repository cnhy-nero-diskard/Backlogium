import { randomBytes } from "node:crypto";
import { readFile, unlink } from "node:fs/promises";
import path from "node:path";
import { mergeDatasets, parseDataset, serializeDataset } from "./merge.mjs";
import { fail, SOURCE } from "./refresh-options.mjs";
import { atomicWrite, fingerprint, guardPaths, hash, readJson, writeJson } from "./refresh-storage.mjs";
import { RequestBudget } from "./refresh-network.mjs";
import { identityFor, resolveLibraries, selectTargets } from "./refresh-targets.mjs";
import { parseGamePage } from "./refresh-parser.mjs";
import { buildReport, safeCategory } from "./refresh-report.mjs";

const STATE_VERSION = 1;
export function optionFingerprint(options) {
  return fingerprint({ steamIds: options.steamIds, appIds: options.appIds, mode: options.mode,
    maxAgeDays: options.maxAgeDays ?? null, maxRequests: options.maxRequests, maxRunSeconds: options.maxRunSeconds });
}
export function createCandidate(base, plan, observations, reviewed) {
  const contributions = [];
  if (plan.addedMappings.length) contributions.push({ schemaVersion: 1, datasetVersion: 0,
    gatheredAt: reviewed.gatheredAt, mappings: plan.addedMappings, lengths: [] });
  for (const [id, observation] of Object.entries(observations)) {
    if (observation.status !== "usable") continue;
    const hltbId = Number(id);
    contributions.push({ schemaVersion: 1, datasetVersion: 0, gatheredAt: observation.gatheredAt,
      mappings: plan.mappings.filter(([, h]) => h === hltbId), lengths: [[hltbId, ...observation.lengths]] });
  }
  return mergeDatasets(base, contributions).dataset;
}
export async function runRefresh(options, dependencies = {}) {
  const source = dependencies.source ?? SOURCE;
  await guardPaths(options, source);
  const baselineText = await readFile(source, "utf8");
  let base, reviewed = null, mappingHash = null;
  try {
    base = parseDataset(baselineText);
    if (serializeDataset(base) !== baselineText) fail("noncanonical-source");
    if (options.reviewedMappings) {
      const text = await readFile(options.reviewedMappings, "utf8");
      reviewed = parseDataset(text); mappingHash = hash(text);
    }
  } catch (error) { if (error.category) throw error; fail("invalid-dataset-input"); }
  const key = (dependencies.env ?? process.env).STEAM_WEB_API_KEY;
  if (typeof key !== "string" || !key.trim()) fail("missing-steam-key");
  const privateStrings = [key, ...options.steamIds];
  const historyFile = path.join(options.stateDir, "history.json");
  let history;
  try { history = await readJson(historyFile, { historyVersion: 1, entries: {} }); }
  catch { history = { historyVersion: 1, entries: {} }; }
  if (history?.historyVersion !== 1 || !history.entries || typeof history.entries !== "object" || Array.isArray(history.entries)) history = { historyVersion: 1, entries: {} };
  let state;
  if (options.resume) {
    state = await readJson(path.join(options.stateDir, "runs", `${options.resume}.json`));
    if (state.stateVersion !== STATE_VERSION || state.runId !== options.resume || state.baselineHash !== hash(baselineText) ||
      state.optionHash !== optionFingerprint(options) || state.mappingHash !== mappingHash ||
      state.planHash !== fingerprint(state.plan)) fail("incompatible-resume");
  }
  const budget = new RequestBudget(options, dependencies, state?.stats);
  if (!state) {
    const scope = await resolveLibraries(options, key, budget);
    const plan = selectTargets(base, reviewed, scope, options, history, budget.now());
    state = { stateVersion: STATE_VERSION, runId: randomBytes(16).toString("hex"), baselineHash: hash(baselineText),
      optionHash: optionFingerprint(options), mappingHash, planHash: fingerprint(plan), plan, observations: {}, stats: budget.snapshot() };
  }
  const plan = state.plan;
  const makeReport = (candidate, candidateText) => buildReport({ options, base, baselineText, candidate, candidateText,
    plan, observations: state.observations, stats: budget.snapshot(), runId: options.dryRun ? null : state.runId, privateStrings });
  const reportExit = (report) => report.outcome === "blocked" ? 1 : report.partialCoverage ? 2 : 0;
  if (!plan.libraries.readable || plan.conflicts.length || options.dryRun) {
    const report = makeReport();
    if (!options.dryRun || options.explicitReport) await writeJson(options.report, report);
    return { report, exitCode: reportExit(report) };
  }
  const checkpointFile = path.join(options.stateDir, "runs", `${state.runId}.json`);
  const checkpoint = async () => { state.stats = budget.snapshot(); await writeJson(checkpointFile, state); };
  await checkpoint();
  // Restore evidence from checkpoints if a crash occurred between observation and history writes.
  const persistHistory = async () => {
    const candidate = createCandidate(base, plan, state.observations, reviewed);
    for (const [id, observation] of Object.entries(state.observations)) {
      if (observation.status !== "usable") continue;
      const numericId = Number(id);
      if (history.entries[id]?.gatheredAt > observation.gatheredAt) continue;
      history.entries[id] = { identity: identityFor(numericId, plan.mappings),
        observedFingerprint: fingerprint([numericId, ...observation.lengths]),
        canonicalFingerprint: fingerprint(candidate.lengths.find(([h]) => h === numericId)), gatheredAt: observation.gatheredAt };
    }
    await writeJson(historyFile, history);
  };
  await persistHistory();
  for (const id of plan.selected) {
    if (state.observations[id]) continue;
    if (budget.stopped) break;
    const attemptsBeforeEntry = budget.stats.requests;
    try {
      const page = await budget.request(`https://howlongtobeat.com/game/${id}`, "hltb");
      const observation = parseGamePage(page, id, privateStrings);
      state.observations[id] = { status: observation.lengths.every((v) => v === null) ? "no-lengths" : "usable",
        gatheredAt: budget.now(), lengths: observation.lengths, title: observation.title };
    } catch (error) {
      if (error.category === "budget-exhausted" && budget.stats.requests === attemptsBeforeEntry) break;
      state.observations[id] = { status: "failed", category: safeCategory(error.category) };
      if (error.category === "challenge-response") budget.stopped = "challenge-response";
    }
    await checkpoint();
    await persistHistory();
    // Test interruption boundary; no callback data reaches public output.
    if (dependencies.afterCheckpoint) await dependencies.afterCheckpoint(state);
  }
  const candidate = createCandidate(base, plan, state.observations, reviewed);
  const candidateText = serializeDataset(candidate);
  if (dependencies.beforeFinalize) await dependencies.beforeFinalize();
  if (hash(await readFile(source, "utf8")) !== state.baselineHash) fail("source-changed");
  const report = makeReport(candidate, candidateText);
  // Each replacement is atomic; the pair is accepted only with both matching hashes.
  // Removing the previous report prevents an interrupted write from looking complete.
  await unlink(options.report).catch((e) => { if (e.code !== "ENOENT") throw e; });
  await atomicWrite(options.output, candidateText);
  if (dependencies.afterCandidateWrite) await dependencies.afterCandidateWrite();
  await writeJson(options.report, report);
  return { report, exitCode: reportExit(report), candidate, runId: state.runId };
}
