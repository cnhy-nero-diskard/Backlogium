import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, symlink, link, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { HELP, parseOptions } from "../refresh-options.mjs";
import { guardPaths } from "../refresh-storage.mjs";
import { main } from "../refresh.mjs";
const id = "76561198000000001";
const args = ["--steam-id", id];
test("strict CLI validation and environment-only key, before any I/O", async () => {
  for (const tail of [["--key", "SECRET"], ["--app-id", "9007199254740992"], ["--app-id", "-1"],
    ["--steam-id", "1"], ["--mode", "other"], ["--mode", "stale"], ["--max-age-days", "3"],
    ["--mode", "stale", "--max-age-days", "Infinity"], ["--max-requests", "0"], ["--resume", "../bad"],
    ["--mode", "stale", "--max-age-days", "2", "--mode", "stale"]]) {
    let calls = 0; let output = "";
    assert.equal(await main([...args, ...tail], { fetch: () => { calls++; }, stderr: (s) => output = s }), 1);
    assert.equal(calls, 0); assert.ok(!output.includes(id)); assert.ok(!output.includes("SECRET"));
  }
  const o = parseOptions([...args, "--mode", "stale", "--max-age-days", "0.5"]);
  assert.equal(o.maxAgeDays, 0.5); assert.match(HELP, /Exit codes: 0.*2.*1/);
  assert.equal(await main(["--help"], { stdout: () => {} }), 0);
});
test("output guards protect source, mapping input, history, symlinks and hard links", async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), "hltb-paths-"));
  try {
    const source = path.join(dir, "source.json"); await writeFile(source, "source bytes");
    const mapping = path.join(dir, "mapping.json"); await writeFile(mapping, "reviewed");
    const o = parseOptions([...args, "--state-dir", path.join(dir, "state")]);
    await guardPaths(o, source);
    for (const output of [source, mapping, o.report, path.join(o.stateDir, "history.json"), path.join(o.stateDir, "runs", "a.json")]) {
      await assert.rejects(guardPaths({ ...o, output, reviewedMappings: mapping }, source));
    }
    const alias = path.join(dir, "alias.json"); await link(source, alias);
    await assert.rejects(guardPaths({ ...o, output: alias }, source));
    const aliasDir = path.join(dir, "alias-dir"); await symlink(dir, aliasDir, process.platform === "win32" ? "junction" : "dir");
    await assert.rejects(guardPaths({ ...o, output: path.join(aliasDir, "source.json") }, source));
    assert.equal(await readFile(source, "utf8"), "source bytes");
  } finally { await rm(dir, { recursive: true, force: true }); }
});
