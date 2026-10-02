import { createHash, randomUUID } from "node:crypto";
import { mkdir, readFile, realpath, rename, stat, unlink, writeFile } from "node:fs/promises";
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
async function resolved(file) {
  try { return await realpath(file); }
  catch (error) {
    if (error.code !== "ENOENT") fail("invalid-output-path");
    return path.join(await resolved(path.dirname(file)), path.basename(file));
  }
}
const pathKey = (file) => process.platform === "win32" ? file.toLowerCase() : file;
const inside = (file, directory) => file === directory || file.startsWith(directory + path.sep);
export async function guardPaths(options, source) {
  const inputs = [source, options.reviewedMappings].filter(Boolean);
  const outputs = [options.output, options.report];
  const [inputKeys, outputKeys, stateKey, repoKey, localKey] = await Promise.all([
    Promise.all(inputs.map(async (f) => pathKey(await resolved(f)))),
    Promise.all(outputs.map(async (f) => pathKey(await resolved(f)))),
    resolved(options.stateDir).then(pathKey), resolved(path.join(TOOL_DIR, "../..")).then(pathKey),
    resolved(path.join(TOOL_DIR, ".local")).then(pathKey),
  ]);
  if (inside(stateKey, repoKey) && !inside(stateKey, localKey)) fail("state-must-be-private");
  const protectedState = await Promise.all(["runs", "history.json"].map(async (name) => pathKey(await resolved(path.join(options.stateDir, name)))));
  if (inputKeys.some((f) => inside(f, stateKey))) fail("state-input-overlap");
  if (outputKeys[0] === outputKeys[1] || outputKeys.some((f) => inputKeys.includes(f) || protectedState.some((p) => inside(f, p)))) fail("output-path-alias");
  // Existing hard links can alias despite different real paths.
  const files = await Promise.all([...inputs, ...outputs, path.join(options.stateDir, "history.json")].map((f) => stat(f).catch((e) => {
    if (e.code === "ENOENT") return null; fail("invalid-output-path");
  })));
  const same = (a, b) => a && b && a.dev === b.dev && a.ino === b.ino;
  for (let i = inputs.length; i < inputs.length + 2; i++) {
    if (files[i]?.isDirectory()) fail("invalid-output-path");
    if (files.some((f, j) => j !== i && same(f, files[i]))) fail("output-path-alias");
  }
}
