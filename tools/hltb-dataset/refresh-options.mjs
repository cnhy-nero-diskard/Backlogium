import path from "node:path";
import { fileURLToPath } from "node:url";

export const TOOL_VERSION = 1;
export const TOOL_DIR = path.dirname(fileURLToPath(import.meta.url));
export const SOURCE = path.join(TOOL_DIR, "dataset.json");
export class RefreshError extends Error {
  constructor(category) { super(category); this.category = category; }
}
export function fail(category) { throw new RefreshError(category); }
export function positiveId(value) {
  if (!/^[1-9]\d*$/.test(String(value)) || !Number.isSafeInteger(Number(value))) fail("invalid-app-id");
  return Number(value);
}
export function steamId(value) {
  if (!/^\d{17}$/.test(value)) fail("invalid-steam-id");
  const id = BigInt(value);
  if (id < 76561197960265728n || id > 76561202255233023n) fail("invalid-steam-id");
  return value;
}
export const HELP = `Usage: node tools/hltb-dataset/refresh.mjs --steam-id <SteamID64> [options]
  --steam-id <id>          Repeat for a union of readable owned libraries
  --app-id <id>            Repeat to intersect that union
  --mode <mode>            missing-only (default), stale, refresh-all
  --max-age-days <number>  Required positive threshold for stale only
  --reviewed-mappings <file>  Schema-v1 reviewed correspondences, not fetch evidence
  --dry-run               Resolve Steam scope; no HLTB/candidate/state/history writes
  --resume <run-id>        Resume a frozen compatible plan (repeat original options)
  --state-dir <directory>  Private state; default tools/hltb-dataset/.local
  --output <file>          Separate candidate; default .local/candidate.json
  --report <file>          Sanitized report; default .local/report.json
  --max-requests <integer> Default 500 attempts, including Steam and retries
  --max-run-seconds <integer> Default 900; each request timeout is at most 15 seconds
  --help                  Show help
Steam credentials: STEAM_WEB_API_KEY environment variable only; never a CLI option.
Requests: concurrency 1, starts at least 1 second apart, at most 2 retries.
Exit codes: 0 complete/clean plan; 2 usable partial/partial plan; 1 blocked/invalid.
Reports disclose public game scope, never account attribution. Schema-v1 gatheredAt
is global metadata, not evidence that every row was fetched. No canonical writes.\n`;

export function parseOptions(args) {
  if (args.length === 1 && ["--help", "-h"].includes(args[0])) return { help: true };
  const options = { steamIds: [], appIds: [], mode: "missing-only", dryRun: false,
    stateDir: path.join(TOOL_DIR, ".local"), maxRequests: 500, maxRunSeconds: 900 };
  const fields = new Map([["--mode", "mode"], ["--max-age-days", "maxAgeDays"],
    ["--reviewed-mappings", "reviewedMappings"], ["--resume", "resume"], ["--state-dir", "stateDir"],
    ["--output", "output"], ["--report", "report"], ["--max-requests", "maxRequests"], ["--max-run-seconds", "maxRunSeconds"]]);
  const seen = new Set();
  for (let i = 0; i < args.length; i++) {
    const flag = args[i];
    if (flag === "--dry-run") { if (seen.has(flag)) fail("duplicate-option"); seen.add(flag); options.dryRun = true; continue; }
    if (!fields.has(flag) && !["--steam-id", "--app-id"].includes(flag)) fail("unknown-option");
    const value = args[++i];
    if (!value || value.startsWith("--")) fail("missing-option-value");
    if (flag === "--steam-id") options.steamIds.push(steamId(value));
    else if (flag === "--app-id") options.appIds.push(positiveId(value));
    else { if (seen.has(flag)) fail("duplicate-option"); seen.add(flag); options[fields.get(flag)] = value; }
  }
  if (!options.steamIds.length) fail("missing-steam-ids");
  if (!["missing-only", "stale", "refresh-all"].includes(options.mode)) fail("invalid-mode");
  if (options.mode === "stale") {
    options.maxAgeDays = Number(options.maxAgeDays);
    if (!Number.isFinite(options.maxAgeDays) || options.maxAgeDays <= 0 || options.maxAgeDays > Number.MAX_SAFE_INTEGER / 86400000) fail("invalid-age-threshold");
  } else if (options.maxAgeDays !== undefined) fail("unexpected-age-threshold");
  for (const field of ["maxRequests", "maxRunSeconds"]) {
    if (!/^[1-9]\d*$/.test(String(options[field])) || !Number.isSafeInteger(Number(options[field]))) fail("invalid-budget");
    options[field] = Number(options[field]);
  }
  if (options.maxRunSeconds > Number.MAX_SAFE_INTEGER / 1000) fail("invalid-budget");
  if (options.resume && !/^[a-f0-9]{32}$/.test(options.resume)) fail("invalid-run-id");
  if (options.resume && options.dryRun) fail("resume-dry-run-conflict");
  options.steamIds = [...new Set(options.steamIds)].sort();
  options.appIds = [...new Set(options.appIds)].sort((a, b) => a - b);
  options.stateDir = path.resolve(options.stateDir);
  options.output = path.resolve(options.output ?? path.join(options.stateDir, "candidate.json"));
  options.explicitReport = seen.has("--report");
  options.report = path.resolve(options.report ?? path.join(options.stateDir, "report.json"));
  if (options.reviewedMappings) options.reviewedMappings = path.resolve(options.reviewedMappings);
  return options;
}
