#!/usr/bin/env node

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { parseDataset, serializeDataset } from "../../../../tools/hltb-dataset/merge.mjs";

const hash = (text) => createHash("sha256").update(text).digest("hex");
const same = (left, right) => JSON.stringify(left) === JSON.stringify(right);
const sorted = (values) => [...values].sort((a, b) => a - b);
const metadata = (dataset, text) => ({
  sha256: hash(text), datasetVersion: dataset.datasetVersion, gatheredAt: dataset.gatheredAt,
});

/** Offline consistency check, not an attestation of live fetching or report counters. */
export function verifyPair(baselineText, candidateText, report) {
  const base = parseDataset(baselineText);
  const candidate = parseDataset(candidateText);
  assert.equal(serializeDataset(base), baselineText);
  assert.equal(serializeDataset(candidate), candidateText);
  assert.equal(report.toolVersion, 1);
  assert.equal(report.reportVersion, 1);
  assert.ok(["complete", "partial"].includes(report.outcome));
  assert.equal(report.partialCoverage, report.outcome === "partial");
  assert.deepEqual(report.baseline, metadata(base, baselineText));
  assert.deepEqual(report.candidate, metadata(candidate, candidateText));

  const oldMappings = new Map(base.mappings);
  const newMappings = new Map(candidate.mappings);
  const oldLengths = new Map(base.lengths.map(([id, ...values]) => [id, values]));
  const newLengths = new Map(candidate.lengths.map(([id, ...values]) => [id, values]));
  for (const [app, id] of oldMappings) assert.equal(newMappings.get(app), id);
  for (const id of oldLengths.keys()) assert.ok(newLengths.has(id));

  const addedMappings = candidate.mappings.filter(([app]) => !oldMappings.has(app));
  const changedLengthIds = candidate.lengths
    .filter(([id, ...values]) => !same(oldLengths.get(id), values)).map(([id]) => id);
  const changedIds = new Set(changedLengthIds);
  const changed = addedMappings.length > 0 || changedLengthIds.length > 0;
  assert.equal(report.changed, changed);
  assert.equal(candidateText !== baselineText, changed);
  assert.equal(candidate.datasetVersion, base.datasetVersion + (changed ? 1 : 0));
  assert.ok(candidate.gatheredAt >= base.gatheredAt);
  if (!changed) assert.equal(candidate.gatheredAt, base.gatheredAt);
  assert.deepEqual(report.addedMappings, addedMappings);
  assert.deepEqual(sorted(report.changedLengthIds), sorted(changedLengthIds));

  const expectedApps = candidate.mappings
    .filter(([app, id]) => !oldMappings.has(app) || changedIds.has(id))
    .map(([appId, hltbId]) => ({ appId, hltbId,
      addedMapping: !oldMappings.has(appId),
      old: oldLengths.get(oldMappings.get(appId)) ?? [null, null, null, null],
      new: newLengths.get(hltbId) ?? [null, null, null, null],
    }));
  // Ignore optional untrusted titles. Direct-target flags require the private plan to verify.
  const actualApps = report.affectedApps.map(({ appId, hltbId, addedMapping, old, new: values }) =>
    ({ appId, hltbId, addedMapping, old, new: values })).sort((a, b) => a.appId - b.appId);
  assert.deepEqual(actualApps, expectedApps);
  assert.deepEqual(report.mappingConflicts, []);
  assert.equal(report.untouched.canonicalApps, base.mappings.length -
    expectedApps.filter(({ addedMapping }) => !addedMapping).length);
  assert.equal(report.untouched.canonicalLengthEntries, base.lengths.length -
    changedLengthIds.filter((id) => oldLengths.has(id)).length);
  return { verified: true, changed, outcome: report.outcome, addedMappings: addedMappings.length,
    changedLengthEntries: changedLengthIds.length, affectedApps: expectedApps.length };
}

async function main(args) {
  if (args.length !== 3) {
    console.error("Usage: node verify-pair.mjs BASELINE CANDIDATE REPORT");
    return 1;
  }
  try {
    const [baselineText, candidateText, reportText] = await Promise.all(args.map((file) => readFile(file, "utf8")));
    const result = verifyPair(baselineText, candidateText, JSON.parse(reportText));
    console.log(JSON.stringify(result));
    return 0;
  } catch {
    // Dataset parser/assertion/I/O messages may contain private input values or paths.
    console.error("Pair verification failed: missing, unsupported, invalid, or inconsistent artifacts. Do not apply or publish.");
    return 1;
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exitCode = await main(process.argv.slice(2));
}
