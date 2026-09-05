import * as logger from "firebase-functions/logger";

/**
 * Firestore is the access-controlled home for observation data: client
 * access is denied and the poller writes through the Admin SDK. Log output
 * has no such boundary — anyone with log-viewer access, any configured
 * sink, and any tool downstream of a sink can read it, and retention or
 * exports may outlive the Firestore state that produced the entry. Every
 * log payload passes through this module specifically so a call site added
 * later cannot reintroduce an identity field by being written somewhere new.
 *
 * Key names alone cannot carry that guarantee: a later caller could smuggle
 * the configured Steam ID or a title under another field name
 * (`{ reason: steamId }`), inside a nested object, or interpolated into the
 * message text (`` `failed for ${steamId}` ``). This module therefore
 * scrubs the sensitive *values* themselves — every string in the message
 * and at any depth of the payload — in addition to dropping identity-named
 * fields. A value that is a bare number is matched on digit boundaries, so
 * a two-digit app ID redacts the number without shredding every unrelated
 * string that happens to contain those digits. Non-plain objects are
 * normalized to plain objects of their own enumerable properties, honouring
 * toJSON() exactly as the underlying logger serializes them, so a class
 * instance cannot smuggle a value past the scrub; Dates stay by reference as
 * intentional safe values, and Errors keep their non-enumerable name,
 * message and stack so a fault stays readable. Property names are scrubbed
 * like values, and a number equal to a registered value is redacted too,
 * since neither a JSON field name nor a numeric app ID is any less readable
 * in Cloud Logging than the string form. Call sites register the
 * concrete values they handle via {@link registerSensitive} so later call
 * sites inherit the rule without restating it.
 */
const IDENTITY_FIELDS = new Set(["steamId", "gameid", "gameName"]);

type IdentityField = "steamId" | "gameid" | "gameName";

/** A log payload that cannot name an account or a title at the type level. */
export type SafeLogPayload = Record<string, unknown> & {
  [K in IdentityField]?: never;
};

const REDACTED = "[redacted]";

/** A registered value that is a bare number, and so needs digit boundaries. */
const NUMERIC = /^\d+$/;

/**
 * Fields whose numeric value is a protocol or Steam enum, never an identity.
 *
 * The numeric rule matches on value, and Steam app IDs collide with HTTP
 * status codes — 400, 500 and 502 are all real app IDs. Without this
 * exemption, a poll that observed Portal (400) would redact the `status` of
 * a later HTTP 400 and lose exactly the fault the spec requires to stay
 * diagnosable. Deliberately narrow: the protection stays value-based, and
 * this is only the small, named set of places where a number cannot be an
 * identity in the first place.
 */
const FAULT_FIELDS = new Set([
  "status",
  "personastate",
  "communityvisibilitystate",
]);

/**
 * Concrete identity values seen so far. Additive and process-wide: the
 * poller serves one configured account, so values are never removed outside
 * tests — a title observed once stays scrubbed if it is ever logged again.
 */
const sensitiveValues = new Set<string>();

/**
 * Remember identity values so later log calls scrub them wherever they
 * appear. Each poll entry point registers what it handles (the configured
 * Steam ID, the observed app ID and title); from then on every message and
 * payload is scrubbed automatically, including on code paths added later
 * that never call this function themselves.
 */
export function registerSensitive(
  ...values: Array<string | null | undefined>
): void {
  for (const value of values) {
    if (typeof value === "string" && value.length > 0) {
      sensitiveValues.add(value);
      orderedCache = null;
    }
  }
}

/** Test hook: production code registers only, and never clears. */
export function clearSensitiveValues(): void {
  sensitiveValues.clear();
  orderedCache = null;
}

/**
 * Registered values longest-first, so a title that contains a shorter
 * registered value is redacted whole rather than in pieces. Cached because
 * every string in every payload is scrubbed against this list, and the set
 * only changes when a call site registers something new.
 */
let orderedCache: string[] | null = null;

function orderedSensitive(): string[] {
  orderedCache ??= [...sensitiveValues].sort((a, b) => b.length - a.length);
  return orderedCache;
}

function isDigit(character: string | undefined): boolean {
  return character !== undefined && character >= "0" && character <= "9";
}

/**
 * Replace every occurrence of one registered value with the redaction marker.
 *
 * A wholly numeric value matches only where it is not flanked by another
 * digit. Steam app IDs are as short as two digits — 10, 20, 70 and 220 are
 * real ones — and a registered value is substituted into every later log
 * string for the life of the instance, so an unbounded match would rewrite
 * `connect ETIMEDOUT 104.16.0.1` into `connect ETIMEDOUT [redacted]4.16.0.1`
 * and cost the diagnosability the spec requires in the same breath as the
 * privacy it requires. Bounding leaves the disclosure closed where it is a
 * disclosure: the ID is still redacted wherever it appears *as* that number
 * (`440`, `appid=440`), and `4400` is a different number, not a leak of 440.
 *
 * Non-numeric values — game titles — still match anywhere, because a title
 * embedded in surrounding text is the title regardless of what abuts it.
 */
function replaceSensitive(text: string, sensitive: string): string {
  const bounded = NUMERIC.test(sensitive);
  let safe = "";
  let cursor = 0;
  let at = text.indexOf(sensitive);

  while (at !== -1) {
    const after = at + sensitive.length;
    const flanked =
      bounded && (isDigit(text[at - 1]) || isDigit(text[after]));
    safe += text.slice(cursor, at) + (flanked ? sensitive : REDACTED);
    cursor = after;
    at = text.indexOf(sensitive, cursor);
  }

  return cursor === 0 ? text : safe + text.slice(cursor);
}

function scrubText(text: string): string {
  let safe = text;
  for (const sensitive of orderedSensitive()) {
    safe = replaceSensitive(safe, sensitive);
  }
  return safe;
}

/**
 * `field` is the property name this value was reached under, or undefined at
 * the payload root and inside arrays. Only the numeric rule consults it.
 */
function scrubValue(
  value: unknown,
  seen = new Set<object>(),
  field?: string,
): unknown {
  if (typeof value === "string") return scrubText(value);
  // A number that is exactly a registered value is that value wearing a
  // different type: `{ app: Number(gameid) }` discloses the app ID just as
  // `{ app: gameid }` does. Exact match rather than the digit-boundary rule
  // strings use — 4400 is a different number, not a leak of 440.
  if (typeof value === "number" || typeof value === "bigint") {
    if (field !== undefined && FAULT_FIELDS.has(field)) return value;
    return sensitiveValues.has(value.toString()) ? REDACTED : value;
  }
  if (Array.isArray(value)) {
    if (seen.has(value)) return "[Circular]";
    seen.add(value);
    try {
      return value.map((entry) => scrubValue(entry, seen));
    } finally {
      seen.delete(value);
    }
  }
  // Dates are intentional safe values (e.g. observedAt) and stay by
  // reference. This precedes the toJSON handling below because Date defines
  // toJSON — calling it here would stringify the Date before logging.
  if (value instanceof Date) return value;
  // `name`, `message` and `stack` are non-enumerable and Error defines no
  // toJSON, so the generic object walk below would serialize a thrown error
  // as `{}` — dropping the fault entirely, against the spec's requirement
  // that faults stay diagnosable. Name them explicitly, along with the
  // equally non-enumerable `cause`, then let the own-keys walk pick up
  // extras such as a Node system error's `code`.
  if (value instanceof Error) {
    if (seen.has(value)) return "[Circular]";
    seen.add(value);
    try {
      const serialized: Record<string, unknown> = {
        name: scrubText(value.name),
        message: scrubText(value.message),
      };
      if (typeof value.stack === "string") {
        serialized["stack"] = scrubText(value.stack);
      }
      if (value.cause !== undefined) {
        serialized["cause"] = scrubValue(value.cause, seen);
      }
      // Same safe-key handling as the generic walk below: an error carrying
      // a dynamic property (`error[steamId] = true`) would otherwise be
      // normalized into a plain object whose field name is the raw identity.
      // The three names above are literals and need no scrubbing.
      for (const key of Object.keys(value)) {
        if (IDENTITY_FIELDS.has(key)) continue;
        serialized[scrubText(key)] = scrubValue(
          (value as unknown as Record<string, unknown>)[key],
          seen,
          key,
        );
      }
      return serialized;
    } finally {
      seen.delete(value);
    }
  }
  if (value !== null && typeof value === "object") {
    if (seen.has(value)) return "[Circular]";
    seen.add(value);
    try {
      // Mirror the pinned firebase-functions logger, which calls an object's
      // toJSON() before walking its properties: scrub what the logger will
      // actually serialize, not the wrapper holding it.
      let toJSON: unknown;
      try {
        toJSON = (value as { toJSON?: unknown }).toJSON;
      } catch {
        return "[Error - cannot serialize]";
      }
      if (typeof toJSON === "function") {
        let serialized: unknown;
        try {
          serialized = (toJSON as () => unknown).call(value);
        } catch {
          return "[Error - cannot serialize]";
        }
        if (serialized === value) return "[Circular]";
        return scrubValue(serialized, seen);
      }
      // Any other object shape — plain or class instance — is normalized to
      // a plain object of its own enumerable properties, which is exactly
      // what the logger's removeCircular() serializes from it.
      //
      // Property names are scrubbed as well as values. A JSON field name is
      // as readable in Cloud Logging as a field value, so `{ [steamId]: true }`
      // discloses exactly what `{ id: steamId }` does.
      const safe: Record<string, unknown> = {};
      for (const key of Object.keys(value)) {
        if (IDENTITY_FIELDS.has(key)) continue;
        const safeKey = scrubText(key);
        let entry: unknown;
        try {
          entry = (value as Record<string, unknown>)[key];
        } catch {
          safe[safeKey] = "[Error - cannot serialize]";
          continue;
        }
        try {
          safe[safeKey] = scrubValue(entry, seen, key);
        } catch {
          safe[safeKey] = "[Error - cannot serialize]";
        }
      }
      return safe;
    } finally {
      seen.delete(value);
    }
  }
  return value;
}

function redact(
  payload?: SafeLogPayload,
): Record<string, unknown> | undefined {
  if (!payload) return payload;

  return scrubValue(payload) as Record<string, unknown>;
}

function emit(
  write: (message: string, payload?: Record<string, unknown>) => void,
  message: string,
  payload?: SafeLogPayload,
): void {
  const safeMessage = scrubText(message);
  const safe = redact(payload);
  if (safe === undefined) {
    write(safeMessage);
  } else {
    write(safeMessage, safe);
  }
}

export function info(message: string, payload?: SafeLogPayload): void {
  emit(logger.info, message, payload);
}

export function warn(message: string, payload?: SafeLogPayload): void {
  emit(logger.warn, message, payload);
}

export function error(message: string, payload?: SafeLogPayload): void {
  emit(logger.error, message, payload);
}
