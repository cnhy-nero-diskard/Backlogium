import { MAX_LENGTH_MINUTES } from "./merge.mjs";
import { fail } from "./refresh-options.mjs";
import { publicTitle } from "./refresh-targets.mjs";

const fields = ["comp_main", "comp_plus", "comp_100", "comp_all"];
export function parseGamePage(html, requestedId, privateStrings = []) {
  if (typeof html !== "string" || /(?:cf-chl-|challenge-platform|captcha|verify you are human|access denied)/i.test(html)) fail("challenge-response");
  const scripts = [...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script\s*>/gi)];
  const script = scripts.find(([, attrs]) => /\bid\s*=\s*["']__NEXT_DATA__["']/i.test(attrs) && /\btype\s*=\s*["']application\/json["']/i.test(attrs));
  if (!script) fail("unrecognized-page");
  let root; try { root = JSON.parse(script[2]); } catch { fail("malformed-page"); }
  const props = root?.props?.pageProps;
  if (!props || props.notFound) fail("not-found");
  // Only documented structured game containers; never regex across unrelated records.
  const containers = [props.game, props.gameData, props.gameInside].filter(Boolean);
  const candidates = containers.flatMap((g) => g.data?.game ?? g).flat().filter((g) => g && typeof g === "object");
  if (!candidates.length) fail("unrecognized-page");
  const rows = candidates.map((game) => {
    const id = typeof game.game_id === "string" && /^[1-9]\d*$/.test(game.game_id) ? Number(game.game_id) : game.game_id;
    if (!Number.isSafeInteger(id) || id !== requestedId) fail("identity-mismatch");
    if (typeof game.game_name !== "string" || !game.game_name.trim()) fail("malformed-page");
    if (!fields.some((f) => Object.hasOwn(game, f))) fail("unrecognized-units");
    const lengths = fields.map((f) => {
      const value = game[f];
      if (value === undefined || value === null || value === 0) return null;
      if (!Number.isSafeInteger(value) || value < 0 || value > MAX_LENGTH_MINUTES * 60) fail("invalid-length");
      return Math.round(value / 60);
    });
    return { hltbId: id, title: publicTitle(game.game_name, privateStrings), lengths };
  });
  if (rows.some((r) => JSON.stringify(r.lengths) !== JSON.stringify(rows[0].lengths))) fail("ambiguous-page");
  return rows[0];
}
