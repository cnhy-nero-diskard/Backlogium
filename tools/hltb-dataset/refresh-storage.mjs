import { createHash, randomUUID } from "node:crypto";
import { link, mkdir, readFile, readdir, realpath, rename, stat, unlink, writeFile } from "node:fs/promises";
import path from "node:path";
import { fail, TOOL_DIR } from "./refresh-options.mjs";

export const hash = (bytes) => createHash("sha256").update(bytes).digest("hex");
export const fingerprint = (value) => hash(JSON.stringify(value));
export async function atomicWrite(file, text) {
  await mkdir(path.dirname(file), { recursive: true });
  const temp = `${file}.tmp-${randomUUID()}`;
  try {
    await writeFile(temp, text, { flag: "wx", mode: 0o600 });
    await rename(temp, file);
  } finally { await unlink(temp).catch((error) => { if (error.code !== "ENOENT") throw error; }); }
}
export const writeJson = (file, value) => atomicWrite(file, JSON.stringify(value, null, 2) + "\n");
export async function readJson(file, fallback) {
  try { return JSON.parse(await readFile(file, "utf8")); }
  catch (error) { if (error.code === "ENOENT" && fallback !== undefined) return fallback; fail("invalid-private-state"); }
}
export async function resolved(file) {
  try { return await realpath(file); }
  catch (error) {
    if (error.code !== "ENOENT") fail("invalid-output-path");
    return path.join(await resolved(path.dirname(file)), path.basename(file));
  }
}
const pathKey = (file) => process.platform === "win32" ? file.toLowerCase() : file;
const inside = (file, directory) => file === directory || file.startsWith(directory + path.sep);
async function protectedStateFiles(root) {
  const found = [];
  const walk = async (dir, inRuns = false) => {
    let entries;
    try { entries = await readdir(dir, { withFileTypes: true }); }
    catch (e) { if (e.code === 'ENOENT') return; fail('invalid-output-path'); }
    for (const entry of entries) {
      const file = path.join(dir, entry.name);
      if (inRuns || entry.name === 'runs' || entry.name === 'history.json') found.push(file);
      // Do not follow directory symlinks while enumerating; their realpath is protected above.
      if (entry.isDirectory()) await walk(file, inRuns || entry.name === 'runs');
    }
  };
  await walk(root); return found;
}
export async function guardPaths(options, source) {
  const inputs = [source, options.reviewedMappings, options.reviewCandidates].filter(Boolean);
  if (options.reviewCandidates) await guardReview(options.reviewCandidates, { source, stateDir: options.stateDir, protectedFiles: [options.output, options.report] });
  const outputs = [options.output, options.report];
  const [inputKeys, outputKeys, stateKey, repoKey, localKey] = await Promise.all([
    Promise.all(inputs.map(async (f) => pathKey(await resolved(f)))),
    Promise.all(outputs.map(async (f) => pathKey(await resolved(f)))),
    resolved(options.stateDir).then(pathKey), resolved(path.join(TOOL_DIR, "../..")).then(pathKey),
    resolved(path.join(TOOL_DIR, ".local")).then(pathKey),
  ]);
  if (inside(stateKey, repoKey) && !inside(stateKey, localKey)) fail("state-must-be-private");
  const stateFiles = await protectedStateFiles(options.stateDir);
  const protectedState = await Promise.all(["runs", "history.json"].map(async (name) => pathKey(await resolved(path.join(options.stateDir, name)))));
  for (const file of stateFiles) {
    const key = pathKey(await resolved(file));
    if (outputKeys.some((f) => inside(f, key))) fail("output-path-alias");
  }
  if (inputKeys.some((f, i) => inputs[i] !== options.reviewCandidates && inside(f, stateKey))) fail("state-input-overlap");
  if (outputKeys[0] === outputKeys[1] || outputKeys.some((f) => inputKeys.includes(f) || protectedState.some((p) => inside(f, p)))) fail("output-path-alias");
  // Existing hard links can alias despite different real paths.
  const files = await Promise.all([...inputs, ...outputs, path.join(options.stateDir, "history.json"), ...stateFiles].map((f) => stat(f).catch((e) => {
    if (e.code === "ENOENT") return null; fail("invalid-output-path");
  })));
  const same = (a, b) => a && b && a.dev === b.dev && a.ino === b.ino;
  for (let i = inputs.length; i < inputs.length + 2; i++) {
    if (files[i]?.isDirectory()) fail("invalid-output-path");
    if (files.some((f, j) => j !== i && same(f, files[i]))) fail("output-path-alias");
  }
}

// Reviews are the sole read-only input allowed under default private state.
// Resolve both directory and inode aliases, including existing checkpoint files.
export async function guardReview(file, { source, stateDir = path.join(TOOL_DIR, '.local'), protectedFiles = [], output = false } = {}) {
  const local = pathKey(await resolved(path.join(TOOL_DIR, '.local')));
  const key = pathKey(await resolved(file));
  if (key === local || !inside(key, local)) fail('review-must-be-private');
  const { execFile } = await import('node:child_process');
  const { promisify } = await import('node:util');
  try {
    await promisify(execFile)('git', ['ls-files', '--error-unmatch', '--', path.relative(TOOL_DIR, file)], { cwd: TOOL_DIR });
    fail('review-must-be-untracked');
  } catch (e) { if (e.category) throw e; if (e.code !== 1) fail('invalid-review-path'); }
  const protectedPaths = [source, ...protectedFiles].filter(Boolean);
  for (const root of new Set([stateDir, path.join(TOOL_DIR, '.local')])) {
    protectedPaths.push(path.join(root, 'history.json'));
    const runs = pathKey(await resolved(path.join(root, 'runs')));
    if (inside(key, runs)) fail('review-state-overlap');
    protectedPaths.push(...await protectedStateFiles(root));
  }
  const info = await stat(file).catch((e) => { if (e.code !== 'ENOENT') fail('invalid-review-path'); return null; });
  for (const p of protectedPaths) {
    if (inside(key, pathKey(await resolved(p)))) fail('review-path-alias');
    const other = await stat(p).catch((e) => { if (e.code !== 'ENOENT') fail('invalid-review-path'); return null; });
    if (info && other && info.dev === other.dev && info.ino === other.ino) fail('review-path-alias');
  }
  if (info?.isDirectory()) fail('invalid-review-path');
  if (output && info) fail('review-already-exists');
}
export async function createPrivateJson(file, value) {
  await mkdir(path.dirname(file), { recursive: true, mode: 0o700 });
  const temp = `${file}.tmp-${randomUUID()}`;
  try {
    await writeFile(temp, JSON.stringify(value, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
    // Link publishes the complete file atomically without replacing an existing decision file.
    await link(temp, file);
  } finally { await unlink(temp).catch((e) => { if (e.code !== 'ENOENT') throw e; }); }
}
