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
 * fields. Call sites register the concrete values they handle via
 * {@link registerSensitive} so later call sites inherit the rule without
 * restating it.
 */
const IDENTITY_FIELDS = new Set(["steamId", "gameid", "gameName"]);

type IdentityField = "steamId" | "gameid" | "gameName";

/** A log payload that cannot name an account or a title at the type level. */
export type SafeLogPayload = Record<string, unknown> & {
  [K in IdentityField]?: never;
};

const REDACTED = "[redacted]";

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
    }
  }
}

/** Test hook: production code registers only, and never clears. */
export function clearSensitiveValues(): void {
  sensitiveValues.clear();
}

function orderedSensitive(): string[] {
  return [...sensitiveValues].sort((a, b) => b.length - a.length);
}

function scrubText(text: string): string {
  let safe = text;
  for (const sensitive of orderedSensitive()) {
    if (safe.includes(sensitive)) {
      safe = safe.split(sensitive).join(REDACTED);
    }
  }
  return safe;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  if (value === null || typeof value !== "object") return false;
  const prototype = Object.getPrototypeOf(value);
  return prototype === Object.prototype || prototype === null;
}

function scrubValue(value: unknown): unknown {
  if (typeof value === "string") return scrubText(value);
  if (Array.isArray(value)) return value.map(scrubValue);
  if (isPlainObject(value)) {
    const safe: Record<string, unknown> = {};
    for (const [key, entry] of Object.entries(value)) {
      if (IDENTITY_FIELDS.has(key)) continue;
      safe[key] = scrubValue(entry);
    }
    return safe;
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
