#!/usr/bin/env node

import { readFile, rename, unlink, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const SCHEMA_VERSION = 1;
export const MAX_LENGTH_MINUTES = 600_000;

const TOP_LEVEL_FIELDS = [
  "schemaVersion",
  "datasetVersion",
  "gatheredAt",
  "mappings",
  "lengths",
];

const scriptPath = fileURLToPath(import.meta.url);
const defaultDatasetPath = path.join(path.dirname(scriptPath), "dataset.json");

function fail(message) {
  throw new Error(message);
}

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function validateSafeInteger(value, label, { positive = false } = {}) {
  if (!Number.isSafeInteger(value) || (positive ? value <= 0 : value < 0)) {
    const constraint = positive ? "a positive safe integer" : "a non-negative safe integer";
    fail(`${label} must be ${constraint}; received ${JSON.stringify(value)}`);
  }
}

function validateExactFields(value, expectedFields, label) {
  const actualFields = Object.keys(value);
  const missing = expectedFields.filter((field) => !actualFields.includes(field));
  const extra = actualFields.filter((field) => !expectedFields.includes(field));

  if (missing.length > 0) {
    fail(`${label} is missing field${missing.length === 1 ? "" : "s"}: ${missing.join(", ")}`);
  }
  if (extra.length > 0) {
    fail(`${label} contains unsupported field${extra.length === 1 ? "" : "s"}: ${extra.join(", ")}`);
  }
}

function validateLength(value, label) {
  if (value === null) return;
  if (!Number.isSafeInteger(value) || value < 0 || value > MAX_LENGTH_MINUTES) {
    fail(
      `${label} must be null or an integer from 0 through ${MAX_LENGTH_MINUTES}; ` +
        `received ${JSON.stringify(value)}`,
    );
  }
}

/** Parse and fully validate one dataset or contribution document. */
export function parseDataset(text, source = "<input>") {
  let value;
  try {
    value = JSON.parse(text);
  } catch (error) {
    fail(`${source}: invalid JSON: ${error.message}`);
  }

  validateDataset(value, source);
  return value;
}

/** Validate a decoded dataset or contribution document. */
export function validateDataset(value, source = "<input>") {
  if (!isPlainObject(value)) {
    fail(`${source}: top level must be a JSON object`);
  }
  validateExactFields(value, TOP_LEVEL_FIELDS, `${source}: top level`);

  if (value.schemaVersion !== SCHEMA_VERSION) {
    fail(`${source}: schemaVersion must be ${SCHEMA_VERSION}; received ${JSON.stringify(value.schemaVersion)}`);
  }
  validateSafeInteger(value.datasetVersion, `${source}: datasetVersion`);
  validateSafeInteger(value.gatheredAt, `${source}: gatheredAt`);

  if (!Array.isArray(value.mappings)) {
    fail(`${source}: mappings must be an array`);
  }
  if (!Array.isArray(value.lengths)) {
    fail(`${source}: lengths must be an array`);
  }
  if (value.gatheredAt === 0 && (value.mappings.length > 0 || value.lengths.length > 0)) {
    fail(`${source}: gatheredAt must be positive when the file contains rows`);
  }

  const mappingAppIds = new Map();
  const mappedHltbIds = new Set();
  for (const [index, row] of value.mappings.entries()) {
    const label = `${source}: mappings row ${index + 1}`;
    if (!Array.isArray(row) || row.length !== 2) {
      fail(`${label} must be [appId,hltbId]`);
    }

    const [appId, hltbId] = row;
    validateSafeInteger(appId, `${label} appId`, { positive: true });
    validateSafeInteger(hltbId, `${label} hltbId`, { positive: true });

    const firstIndex = mappingAppIds.get(appId);
    if (firstIndex !== undefined) {
      fail(`${label} duplicates appId ${appId} first declared at mappings row ${firstIndex + 1}`);
    }
    mappingAppIds.set(appId, index);
    mappedHltbIds.add(hltbId);
  }

  const lengthHltbIds = new Map();
  for (const [index, row] of value.lengths.entries()) {
    const label = `${source}: lengths row ${index + 1}`;
    if (!Array.isArray(row) || row.length !== 5) {
      fail(
        `${label} must be ` +
          "[hltbId,mainStoryMinutes,mainExtraMinutes,completionistMinutes,allStylesMinutes]",
      );
    }

    const [hltbId, ...completionLengths] = row;
    validateSafeInteger(hltbId, `${label} hltbId`, { positive: true });
    completionLengths.forEach((length, lengthIndex) => {
      const fieldNames = [
        "mainStoryMinutes",
        "mainExtraMinutes",
        "completionistMinutes",
        "allStylesMinutes",
      ];
      validateLength(length, `${label} ${fieldNames[lengthIndex]}`);
    });

    if (completionLengths.every((length) => length === null)) {
      fail(`${label} has no known lengths; omit the lengths row and retain its mapping instead`);
    }

    const firstIndex = lengthHltbIds.get(hltbId);
    if (firstIndex !== undefined) {
      fail(`${label} duplicates hltbId ${hltbId} first declared at lengths row ${firstIndex + 1}`);
    }
    if (!mappedHltbIds.has(hltbId)) {
      fail(`${label} has hltbId ${hltbId} but no mapping in this file refers to it`);
    }
    lengthHltbIds.set(hltbId, index);
  }

  return value;
}

function compareNumbers(left, right) {
  return left - right;
}

function compareRows(left, right) {
  const leftText = JSON.stringify(left);
  const rightText = JSON.stringify(right);
  if (leftText === rightText) return 0;
  return leftText < rightText ? -1 : 1;
}

function rowsEqual(left, right) {
  return JSON.stringify(left) === JSON.stringify(right);
}

function relationEqual(left, right) {
  return left.length === right.length && left.every((row, index) => rowsEqual(row, right[index]));
}

function canonicalRelations(dataset) {
  return {
    mappings: dataset.mappings.map((row) => [...row]).sort((left, right) => compareNumbers(left[0], right[0])),
    lengths: dataset.lengths.map((row) => [...row]).sort((left, right) => compareNumbers(left[0], right[0])),
  };
}

/**
 * Merge validated contributions into a validated base dataset.
 *
 * Contribution entries may be either decoded documents or
 * `{ data, source }` records. The latter produces more useful conflict errors.
 */
export function mergeDatasets(base, contributionEntries = [], baseSource = "<dataset>") {
  validateDataset(base, baseSource);
  const contributions = contributionEntries.map((entry, index) => {
    const wrapped = isPlainObject(entry) && "data" in entry;
    const data = wrapped ? entry.data : entry;
    const source = wrapped && entry.source ? entry.source : `<contribution ${index + 1}>`;
    validateDataset(data, source);
    return { data, source };
  });

  const baseCanonical = canonicalRelations(base);
  const baseMappingAppIds = new Set(baseCanonical.mappings.map(([appId]) => appId));
  const mappingByAppId = new Map();
  const mappingOriginByAppId = new Map();
  const newMappingGatheredAt = new Map();

  for (const [appId, hltbId] of baseCanonical.mappings) {
    mappingByAppId.set(appId, hltbId);
    mappingOriginByAppId.set(appId, baseSource);
  }

  for (const { data, source } of contributions) {
    for (const [index, [appId, hltbId]] of data.mappings.entries()) {
      const existingHltbId = mappingByAppId.get(appId);
      if (existingHltbId !== undefined && existingHltbId !== hltbId) {
        const existingSource = mappingOriginByAppId.get(appId);
        fail(
          `correspondence conflict for appId ${appId}: ` +
            `${existingSource} maps it to hltbId ${existingHltbId}, while ` +
            `${source} mappings row ${index + 1} maps it to hltbId ${hltbId}`,
        );
      }

      if (existingHltbId === undefined) {
        mappingByAppId.set(appId, hltbId);
        mappingOriginByAppId.set(appId, `${source} mappings row ${index + 1}`);
      }
      if (!baseMappingAppIds.has(appId)) {
        newMappingGatheredAt.set(
          appId,
          Math.max(newMappingGatheredAt.get(appId) ?? 0, data.gatheredAt),
        );
      }
    }
  }

  const lengthCandidates = new Map();
  for (const row of baseCanonical.lengths) {
    lengthCandidates.set(row[0], {
      row,
      gatheredAt: base.gatheredAt,
      source: baseSource,
    });
  }

  for (const { data, source } of contributions) {
    for (const [index, row] of data.lengths.entries()) {
      const candidate = {
        row: [...row],
        gatheredAt: data.gatheredAt,
        source: `${source} lengths row ${index + 1}`,
      };
      const current = lengthCandidates.get(row[0]);
      if (
        current === undefined ||
        candidate.gatheredAt > current.gatheredAt ||
        (candidate.gatheredAt === current.gatheredAt && compareRows(candidate.row, current.row) > 0)
      ) {
        lengthCandidates.set(row[0], candidate);
      }
    }
  }

  const mappings = [...mappingByAppId.entries()]
    .map(([appId, hltbId]) => [appId, hltbId])
    .sort((left, right) => compareNumbers(left[0], right[0]));
  const lengths = [...lengthCandidates.values()]
    .map(({ row }) => row)
    .sort((left, right) => compareNumbers(left[0], right[0]));

  const mappingsChanged = !relationEqual(baseCanonical.mappings, mappings);
  const lengthsChanged = !relationEqual(baseCanonical.lengths, lengths);
  const changed = mappingsChanged || lengthsChanged;

  if (!changed) {
    return {
      changed: false,
      dataset: {
        schemaVersion: base.schemaVersion,
        datasetVersion: base.datasetVersion,
        gatheredAt: base.gatheredAt,
        mappings,
        lengths,
      },
    };
  }

  if (base.datasetVersion === Number.MAX_SAFE_INTEGER) {
    fail(`${baseSource}: datasetVersion cannot be incremented beyond Number.MAX_SAFE_INTEGER`);
  }

  const changeTimes = [...newMappingGatheredAt.values()];
  const baseLengthsById = new Map(baseCanonical.lengths.map((row) => [row[0], row]));
  for (const [hltbId, candidate] of lengthCandidates) {
    const baseRow = baseLengthsById.get(hltbId);
    if (baseRow === undefined || !rowsEqual(baseRow, candidate.row)) {
      changeTimes.push(candidate.gatheredAt);
    }
  }

  return {
    changed: true,
    dataset: {
      schemaVersion: SCHEMA_VERSION,
      datasetVersion: base.datasetVersion + 1,
      gatheredAt: changeTimes.reduce(
        (newestGatheredAt, gatheredAt) => Math.max(newestGatheredAt, gatheredAt),
        base.gatheredAt,
      ),
      mappings,
      lengths,
    },
  };
}

function serializeRelation(name, rows, trailingComma) {
  if (rows.length === 0) {
    return [`  "${name}": []${trailingComma ? "," : ""}`];
  }

  return [
    `  "${name}": [`,
    ...rows.map((row, index) => {
      const suffix = index === rows.length - 1 ? "" : ",";
      return `    [${row.map((value) => (value === null ? "null" : value)).join(",")}]${suffix}`;
    }),
    `  ]${trailingComma ? "," : ""}`,
  ];
}

/** Serialize using the one canonical field order, row order, whitespace, and LF ending. */
export function serializeDataset(dataset) {
  validateDataset(dataset, "<output>");
  const canonical = canonicalRelations(dataset);
  return [
    "{",
    `  "schemaVersion": ${dataset.schemaVersion},`,
    `  "datasetVersion": ${dataset.datasetVersion},`,
    `  "gatheredAt": ${dataset.gatheredAt},`,
    ...serializeRelation("mappings", canonical.mappings, true),
    ...serializeRelation("lengths", canonical.lengths, false),
    "}",
    "",
  ].join("\n");
}

async function writeAtomically(destination, contents) {
  const temporary = `${destination}.tmp-${process.pid}`;
  try {
    await writeFile(temporary, contents, { encoding: "utf8", flag: "wx" });
    await rename(temporary, destination);
  } finally {
    await unlink(temporary).catch((error) => {
      if (error.code !== "ENOENT") throw error;
    });
  }
}

function usage() {
  return [
    "Usage:",
    "  node tools/hltb-dataset/merge.mjs [dataset.json] [contribution.json ...]",
    "  node tools/hltb-dataset/merge.mjs --check [dataset.json] [contribution.json ...]",
    "  node tools/hltb-dataset/merge.mjs --output <path> <dataset.json> [contribution.json ...]",
  ].join("\n");
}

function parseArguments(args) {
  const positional = [];
  let check = false;
  let output;

  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index];
    if (argument === "--check") {
      check = true;
    } else if (argument === "--output") {
      output = args[index + 1];
      if (!output) fail("--output requires a path");
      index += 1;
    } else if (argument === "--help" || argument === "-h") {
      return { help: true };
    } else if (argument.startsWith("-")) {
      fail(`unknown option: ${argument}\n${usage()}`);
    } else {
      positional.push(argument);
    }
  }

  if (check && output) fail("--check and --output cannot be used together");
  return { check, output, positional };
}

async function main() {
  const { check, output, positional, help } = parseArguments(process.argv.slice(2));
  if (help) {
    console.log(usage());
    return;
  }

  const datasetPath = path.resolve(positional[0] ?? defaultDatasetPath);
  const contributionPaths = positional.slice(1).map((entry) => path.resolve(entry));
  const datasetText = await readFile(datasetPath, "utf8");
  const base = parseDataset(datasetText, datasetPath);
  const contributions = await Promise.all(
    contributionPaths.map(async (contributionPath) => ({
      data: parseDataset(await readFile(contributionPath, "utf8"), contributionPath),
      source: contributionPath,
    })),
  );

  const result = mergeDatasets(base, contributions, datasetPath);
  const regenerated = serializeDataset(result.dataset);

  if (check) {
    if (datasetText !== regenerated) {
      fail(
        `${datasetPath}: committed output differs from validate-and-regenerate output; ` +
          "run merge.mjs without --check and commit the result",
      );
    }
    console.log(`Validated ${datasetPath}; regenerated output is byte-identical.`);
    return;
  }

  if (!output && contributionPaths.length > 0 && !result.changed) {
    console.log(`All contributions are redundant; ${datasetPath} is unchanged.`);
    return;
  }

  const destination = path.resolve(output ?? datasetPath);
  if (destination === datasetPath && datasetText === regenerated) {
    console.log(`${datasetPath} is already canonical; no write needed.`);
    return;
  }

  await writeAtomically(destination, regenerated);
  console.log(
    `Wrote datasetVersion ${result.dataset.datasetVersion} with ` +
      `${result.dataset.mappings.length} mappings and ${result.dataset.lengths.length} length rows to ${destination}.`,
  );
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(scriptPath)) {
  main().catch((error) => {
    console.error(`Error: ${error.message}`);
    process.exitCode = 1;
  });
}
