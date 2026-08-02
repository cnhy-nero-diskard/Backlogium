# Design: opt-in live monitor

## Trigger and lifetime

```
Settings toggle ON (app visible) ──> PresenceService starts
                                      │
                                      ├─ no game: poll every 30s, show "Monitoring Steam"
                                      ├─ game:    poll every 30s, show "Playing X · 47m"
                                      └─ toggle OFF while idle: stop service
```

The existing `PresenceService` is retained rather than introducing a second polling service. It
already owns the 30-second cadence, its notification channel, and Android 15's `dataSync` timeout
handling. The new setting changes only its idle behavior: when `liveMonitorEnabled` is true,
`NowPlaying.NotPlaying` keeps the service alive and shows the monitoring notification; when false,
the established behavior of stopping at not-playing remains.

The setting is intentionally **off by default** and can only start the service through a visible
Settings interaction or a later app foreground. Background service starts remain forbidden: a
persisted enabled setting alone must not try to resurrect the service from a worker or boot event.

## State transitions

| Setting | Steam result | Service outcome | Notification |
|---|---|---|---|
| off | in game | existing tracked-session behavior | Playing game |
| off | not playing | stop | cleared |
| on | in game | continue polling | Playing game |
| on | not playing | continue polling | Monitoring Steam |
| switched off while idle | not playing | stop | cleared |
| switched off while in game | in game | continue until game ends | Playing game |

The in-game exception when toggled off makes the new setting control *pre-game monitoring*, not
erase an already-recorded live session. Once Steam reports not-playing, normal shutdown applies.

## Settings contract

`SettingsRepository` exposes `liveMonitorEnabled: Flow<Boolean>` and a suspend setter. The
Settings ViewModel combines it into state and starts the service on an enabled toggle. The UI says
that it polls every 30 seconds, needs an ongoing notification, may use battery/data, and is subject
to Android's roughly six-hour background-service allowance.

The monitor notification uses the existing channel and notification id, so it is updated in place
instead of stacking a second notification. It is shown even without `POST_NOTIFICATIONS` because a
foreground service must supply its initial notification; Android controls its visibility.

## Verification

1. Toggle Live monitor on while no game runs; observe the ongoing monitoring notification.
2. Start a game without reopening Backlogium; within one 30-second interval the notification and
   app state change to the running game.
3. End the game; monitoring notification returns while the toggle remains on.
4. Turn the toggle off while idle; the notification disappears and requests stop.
5. Turn it off during a game; elapsed tracking continues until Steam reports the game ended.
6. Stop the service, foreground the app with monitor enabled; it resumes.
