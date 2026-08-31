import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
  MAX_LENGTH_MINUTES,
  mergeDatasets,
  parseDataset,
  serializeDataset,
} from "../merge.mjs";

const testDirectory = path.dirname(fileURLToPath(import.meta.url));
const toolDirectory = path.dirname(testDirectory);
const fixtureDirectory = path.join(testDirectory, "fixtures");
const mergeScript = path.join(toolDirectory, "merge.mjs");
const appExportGolden = path.resolve(
  toolDirectory,
  "..",
  "..",
  "app",
  "src",
  "test",
  "resources",
  "com",
  "example",
  "backlogium",
  "data",
  "hltb",
  "backlogium-hltb-contribution.json",
);

function fixtureText(name) {
  return readFileSync(path.join(fixtureDirectory, name), "utf8");
}

function fixture(name) {
  return parseDataset(fixtureText(name), name);
}

function contribution(name) {
  return { data: fixture(name), source: name };
}

test("the hand-written two-row sample is a clean contribution", () => {
  const sample = fixture("sample-two-row.json");

  assert.equal(sample.mappings.length, 2);
  assert.equal(sample.lengths.length, 2);
  assert.equal(serializeDataset(sample), fixtureText("sample-two-row.json"));
});

test("the exact Android contribution golden is canonical schema-v1 input", () => {
  const goldenText = readFileSync(appExportGolden, "utf8");
  const golden = parseDataset(goldenText, "Android contribution golden");

  assert.deepEqual(Object.keys(golden), [
    "schemaVersion",
    "datasetVersion",
    "gatheredAt",
    "mappings",
    "lengths",
  ]);
  assert.equal(golden.datasetVersion, 0);
  assert.equal(serializeDataset(golden), goldenText);
});

for (const rejection of [
  {
    fixture: "invalid-app-id.json",
    message: /invalid-app-id\.json: mappings row 1 appId must be a positive safe integer/,
  },
  {
    fixture: "invalid-hltb-id.json",
    message: /invalid-hltb-id\.json: mappings row 1 hltbId must be a positive safe integer/,
  },
  {
    fixture: "negative-length.json",
    message: /negative-length\.json: lengths row 1 mainStoryMinutes must be null or an integer/,
  },
  {
    fixture: "over-ceiling-length.json",
    message: new RegExp(
      `over-ceiling-length\\.json: lengths row 1 mainStoryMinutes must be null or an integer from 0 through ${MAX_LENGTH_MINUTES}`,
    ),
  },
  {
    fixture: "duplicate-app-id.json",
    message: /duplicate-app-id\.json: mappings row 2 duplicates appId 10.*mappings row 1/,
  },
]) {
  test(`validation rejects ${rejection.fixture} and identifies its row`, () => {
    assert.throws(
      () => parseDataset(fixtureText(rejection.fixture), rejection.fixture),
      rejection.message,
    );
  });
}

test("validation rejects personal or otherwise unsupported fields", () => {
  assert.throws(
    () => parseDataset(fixtureText("personal-field.json"), "personal-field.json"),
    /top level contains unsupported field: playtimeMinutes/,
  );
});

test("merge adds a new correspondence and its lengths", () => {
  const result = mergeDatasets(fixture("base.json"), [contribution("add.json")], "base.json");

  assert.equal(result.changed, true);
  assert.equal(result.dataset.datasetVersion, 4);
  assert.equal(result.dataset.gatheredAt, 1700000100000);
  assert.deepEqual(result.dataset.mappings, [
    [10, 100],
    [20, 200],
  ]);
  assert.deepEqual(result.dataset.lengths, [
    [100, 100, 200, 300, 250],
    [200, 400, 500, 600, 525],
  ]);
});

test("newest gathered-at wins length drift independent of contribution order", () => {
  const base = fixture("base.json");
  const older = contribution("length-drift-older.json");
  const newer = contribution("length-drift-newer.json");
  const forward = mergeDatasets(base, [older, newer], "base.json");
  const reverse = mergeDatasets(base, [newer, older], "base.json");

  assert.deepEqual(forward.dataset.lengths, [[100, 120, 220, 320, 270]]);
  assert.equal(serializeDataset(forward.dataset), serializeDataset(reverse.dataset));
});

test("equal gathered-at length drift uses a deterministic tuple tie-break", () => {
  const base = fixture("base.json");
  const left = {
    ...fixture("length-drift-older.json"),
    lengths: [[100, 111, 211, 311, 261]],
  };
  const right = {
    ...fixture("length-drift-older.json"),
    lengths: [[100, 112, 212, 312, 262]],
  };

  const first = mergeDatasets(base, [left, right]);
  const second = mergeDatasets(base, [right, left]);
  assert.equal(serializeDataset(first.dataset), serializeDataset(second.dataset));
  assert.deepEqual(first.dataset.lengths, right.lengths);
});

test("correspondence conflict blocks and reports both mappings", () => {
  assert.throws(
    () => mergeDatasets(fixture("base.json"), [contribution("conflict.json")], "base.json"),
    /appId 10: base\.json maps it to hltbId 100, while conflict\.json mappings row 1 maps it to hltbId 999/,
  );
});

test("a fully redundant contribution leaves canonical bytes unchanged", () => {
  const baseText = fixtureText("base.json");
  const result = mergeDatasets(
    parseDataset(baseText, "base.json"),
    [contribution("redundant.json")],
    "base.json",
  );

  assert.equal(result.changed, false);
  assert.equal(serializeDataset(result.dataset), baseText);
});

test("merging the same inputs twice is byte-identical", () => {
  const contributions = [
    contribution("add.json"),
    contribution("length-drift-older.json"),
    contribution("length-drift-newer.json"),
  ];
  const first = mergeDatasets(fixture("base.json"), contributions, "base.json");
  const firstBytes = serializeDataset(first.dataset);
  const second = mergeDatasets(
    parseDataset(firstBytes, "first-output.json"),
    contributions,
    "first-output.json",
  );

  assert.equal(serializeDataset(second.dataset), firstBytes);
});

test("reordering non-conflicting contributions is byte-identical", () => {
  const base = fixture("base.json");
  const add = contribution("add.json");
  const drift = contribution("length-drift-newer.json");

  const first = mergeDatasets(base, [add, drift], "base.json");
  const second = mergeDatasets(base, [drift, add], "base.json");
  assert.equal(serializeDataset(first.dataset), serializeDataset(second.dataset));
});

test("the validate-and-regenerate gate accepts canonical output", () => {
  const result = spawnSync(process.execPath, [mergeScript, "--check", path.join(toolDirectory, "dataset.json")], {
    encoding: "utf8",
  });

  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /regenerated output is byte-identical/);
});

test("the validate-and-regenerate gate rejects a deliberately unsorted dataset", () => {
  const result = spawnSync(
    process.execPath,
    [mergeScript, "--check", path.join(fixtureDirectory, "unsorted-dataset.json")],
    { encoding: "utf8" },
  );

  assert.equal(result.status, 1);
  assert.match(result.stderr, /committed output differs from validate-and-regenerate output/);
});
