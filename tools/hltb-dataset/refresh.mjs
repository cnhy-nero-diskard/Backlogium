#!/usr/bin/env node
import path from "node:path";
import { fileURLToPath } from "node:url";
import { HELP, parseOptions, RefreshError } from "./refresh-options.mjs";

export async function main(args = process.argv.slice(2), dependencies = {}) {
  try {
    const options = parseOptions(args);
    if (options.help) { (dependencies.stdout ?? console.log)(HELP); return 0; }
    const { runRefresh } = await import("./refresh-run.mjs");
    const result = await runRefresh(options, dependencies);
    (dependencies.stdout ?? console.log)(JSON.stringify(result.report, null, 2));
    return result.exitCode;
  } catch (error) {
    // Never serialize upstream errors, arguments, paths, or response bodies.
    const category = error instanceof RefreshError ? error.category : "execution-failed";
    (dependencies.stderr ?? console.error)(JSON.stringify({ reportVersion: 1, outcome: "blocked", category }));
    return 1;
  }
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) process.exitCode = await main();
