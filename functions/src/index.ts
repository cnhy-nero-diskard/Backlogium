import { initializeApp } from "firebase-admin/app";
import { defineSecret, defineString } from "firebase-functions/params";
import { onSchedule } from "firebase-functions/v2/scheduler";
import * as logger from "firebase-functions/logger";
import { fetchPresence } from "./steam";
import { recordObservation } from "./presence";

initializeApp();

/**
 * Read from Secret Manager at runtime. The value never appears in source,
 * in function environment config, or in git history.
 */
const STEAM_API_KEY = defineSecret("STEAM_API_KEY");

/**
 * Single user, one value, changes never — configuration, not data. Storing
 * it in Firestore would imply a registration model this project does not have.
 */
const STEAM_ID = defineString("STEAM_ID");

export const pollPresence = onSchedule(
  {
    schedule: "* * * * *",
    timeZone: "Etc/UTC",
    // Must match the Firestore database location, which is permanent.
    region: "asia-southeast1",
    secrets: [STEAM_API_KEY],
    memory: "256MiB",
    timeoutSeconds: 60,
    // No retries. The next poll is 60 seconds away, so a retry buys nothing
    // and would only record a near-duplicate observation timestamp.
    retryCount: 0,
  },
  async () => {
    const steamId = STEAM_ID.value();

    if (!steamId) {
      logger.error("STEAM_ID is not configured; nothing to poll");
      return;
    }

    const observation = await fetchPresence(STEAM_API_KEY.value(), steamId);

    if (observation === null) {
      // No information. fetchPresence has already logged why. Stored state
      // is deliberately left untouched rather than recorded as offline.
      return;
    }

    const outcome = await recordObservation(steamId, observation);

    // Liveness heartbeat. Emitted only after a successful Steam fetch AND a
    // successful Firestore interaction, so its absence means the pipeline is
    // broken somewhere — not merely that the user has not played recently.
    //
    // This exists because the cheap signals do not work. Invocation count
    // stays at a perfect 1,440/day if the Steam key is revoked, since the
    // function still runs and still returns 200. And `updatedAt` cannot
    // report health because a healthy poller writes nothing while idle.
    //
    // A metric-absence alert on this line is the monitoring hook.
    logger.info("poll ok", { outcome, gameid: observation.gameid });
  },
);
