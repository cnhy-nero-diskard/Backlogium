import * as logger from "firebase-functions/logger";

/**
 * Firestore is the access-controlled home for observation data: client
 * access is denied and the poller writes through the Admin SDK. Log output
 * has no such boundary — anyone with log-viewer access, any configured
 * sink, and any tool downstream of a sink can read it, and retention or
 * exports may outlive the Firestore state that produced the entry. Every
 * log payload passes through this module specifically so a call site added
 * later cannot reintroduce an identity field by being written somewhere new.
 */
const IDENTITY_FIELDS = new Set(["steamId", "gameid", "gameName"]);

type IdentityField = "steamId" | "gameid" | "gameName";

/** A log payload that cannot name an account or a title at the type level. */
export type SafeLogPayload = Record<string, unknown> & {
  [K in IdentityField]?: never;
};

function redact(
  payload?: SafeLogPayload,
): Record<string, unknown> | undefined {
  if (!payload) return payload;

  const safe: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(payload)) {
    if (IDENTITY_FIELDS.has(key)) continue;
    safe[key] = value;
  }
  return safe;
}

function emit(
  write: (message: string, payload?: Record<string, unknown>) => void,
  message: string,
  payload?: SafeLogPayload,
): void {
  const safe = redact(payload);
  if (safe === undefined) {
    write(message);
  } else {
    write(message, safe);
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
