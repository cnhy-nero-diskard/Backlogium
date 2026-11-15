import { fail } from "./refresh-options.mjs";
import { fingerprint } from "./refresh-storage.mjs";

export function publicTitle(value, privateStrings = []) {
  if (typeof value !== "string" || !value.trim() || value.length > 200 ||
    /[\x00-\x1f\x7f\\<>]|(?:https?:|www\.|steamcommunity|[A-Za-z]:\/)|\b\d{17}\b|[a-f0-9]{32}|(?:api[_ -]?key|password|token|secret)\s*[:=]/i.test(value) ||
    privateStrings.some((s) => s && value.toLowerCase().includes(s.toLowerCase()))) return null;
  return value.trim();
}
export function parseLibrary(text, privateStrings = []) {
  let data; try { data = JSON.parse(text)?.response; } catch { return null; }
  if (!data || !Number.isSafeInteger(data.game_count) || data.game_count < 0) return null;
  if (data.game_count === 0 && (data.games === undefined || (Array.isArray(data.games) && data.games.length === 0))) return [];
  if (!Array.isArray(data.games) || data.games.length !== data.game_count) return null;
  const apps = new Map();
  for (const game of data.games) {
    if (!Number.isSafeInteger(game?.appid) || game.appid <= 0 || apps.has(game.appid)) return null;
    apps.set(game.appid, { appId: game.appid, title: publicTitle(game.name, privateStrings) });
  }
  return [...apps.values()];
}
export async function resolveLibraries(options, key, budget) {
  const libraries = { requested: options.steamIds.length, readable: 0, unavailable: 0, empty: 0 };
  const apps = new Map();
  for (const id of options.steamIds) {
    let games = null;
    try {
      const url = new URL("https://api.steampowered.com/IPlayerService/GetOwnedGames/v1/");
      url.search = new URLSearchParams({ key, steamid: id, include_appinfo: "true", include_played_free_games: "true", format: "json" }).toString();
      games = parseLibrary(await budget.request(url.toString(), "steam"), [key, ...options.steamIds]);
    } catch { /* Public output is aggregate-only, never an upstream error. */ }
    if (games === null) libraries.unavailable++;
    else {
      libraries.readable++; if (!games.length) libraries.empty++;
      for (const game of games) if (!apps.has(game.appId) || !apps.get(game.appId).title) apps.set(game.appId, game);
    }
  }
  const restricted = new Set(options.appIds);
  return { libraries, apps: [...apps.values()].filter((g) => !restricted.size || restricted.has(g.appId)).sort((a, b) => a.appId - b.appId) };
}
export function identityFor(id, mappings) {
  return fingerprint(mappings.filter(([, h]) => h === id).sort((a, b) => a[0] - b[0]));
}
export function selectTargets(base, reviewed, scope, options, history, now) {
  const mappings = new Map(base.mappings); const conflicts = [];
  // Validate correspondence disagreements globally, even if outside selected scope.
  for (const [appId, id] of reviewed?.mappings ?? []) {
    if (mappings.has(appId) && mappings.get(appId) !== id) conflicts.push({ appId, existingHltbId: mappings.get(appId), reviewedHltbId: id });
  }
  const appIds = new Set(scope.apps.map((g) => g.appId)); const addedMappings = [];
  for (const row of reviewed?.mappings ?? []) if (appIds.has(row[0]) && !mappings.has(row[0])) { addedMappings.push(row); mappings.set(...row); }
  addedMappings.sort((a, b) => a[0] - b[0]);
  const allMappings = [...mappings].sort((a, b) => a[0] - b[0]);
  const lengths = new Map(base.lengths.map((r) => [r[0], r]));
  const unique = [...new Set(scope.apps.map((a) => mappings.get(a.appId)).filter(Boolean))].sort((a, b) => a - b);
  const selected = unique.filter((id) => {
    if (!lengths.has(id) || options.mode === "refresh-all") return true;
    if (options.mode === "missing-only") return false;
    const evidence = history.historyVersion === 1 ? history.entries?.[id] : null;
    return !evidence || evidence.identity !== identityFor(id, allMappings) ||
      !/^[a-f0-9]{64}$/.test(evidence.observedFingerprint ?? "") ||
      evidence.canonicalFingerprint !== fingerprint(lengths.get(id)) ||
      !Number.isSafeInteger(evidence.gatheredAt) || evidence.gatheredAt > now ||
      now - evidence.gatheredAt >= options.maxAgeDays * 86400000;
  });
  return { ...scope, mappings: allMappings, addedMappings, selected, uniqueHltbIds: unique.length,
    modeExcluded: unique.length - selected.length, conflicts,
    unmapped: scope.apps.filter((a) => !mappings.has(a.appId)) };
}
