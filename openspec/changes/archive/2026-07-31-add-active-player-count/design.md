## Context

`GameDetailViewModel` (app/src/main/java/com/example/backlogium/ui/gamedetail/GameDetailViewModel.kt)
builds its `GameSummaryUi` by combining `gameRepository.library`, `achievementRepository.observeForGame`,
and `settings.ruleConfig` — all local, Flow-backed, offline-safe. `SteamApi` (data/remote/SteamApi.kt)
already talks to `api.steampowered.com`; every existing call there takes the user's own `key` +
`steamid`. `LiveStatusRepository` establishes the app's pattern for a live, non-persisted fact:
fetch, emit, never write to Room, and explicitly document why persisting would let it go stale and
"lie" (its own words, in its class doc).

`GetNumberOfCurrentPlayers` doesn't fit the existing `SteamApi` calling convention exactly — it
takes no key, only `appid` — but otherwise it is the same Retrofit interface and the same base URL.

## Goals / Non-Goals

**Goals:**
- Show a game's current Steam concurrent-player count on its detail screen when Steam has one.
- Never show a fabricated or stale number — absence renders as nothing, not zero.

**Non-Goals:**
- Real-time/polling updates while the screen is open.
- Showing player counts anywhere other than the game detail screen (Library rows, leaderboards,
  trend charts are all out of scope).
- Persisting the count, in Room or anywhere else.

## Decisions

- **One-shot fetch on screen load, not polled.** `GameDetailViewModel` fetches the count once when
  `appId` resolves and holds it in `StateFlow` state alongside the rest of `GameSummaryUi`.
  *Why:* concurrent-player counts drift over minutes/hours, not seconds — nobody watches this
  number tick. `LiveStatusRepository`'s 30-second poll exists because `NowPlaying` drives a
  same-session banner that can flip while a screen is open; this has no equivalent need.
  *Alternative rejected:* a small `PlayerCountRepository` mirroring `LiveStatusRepository`'s
  `flow{} + shareIn(WhileSubscribed)` machinery — that pattern earns its complexity by serving
  multiple concurrent observers (Home banner + shell header) off one poll. Here there is exactly
  one observer (`GameDetailViewModel`, once), so the machinery would be pure overhead.

- **Lives as a `GameRepository` suspend function, not a new repository.** `suspend fun
  currentPlayerCount(appId: Long): Int?`, calling `SteamApi.getNumberOfCurrentPlayers` and
  collapsing every failure mode (network error, non-1 `result`, missing `player_count`) to `null`.
  *Why:* `GameRepository` already owns everything else about a `Game` row; this is one more fact
  about a game, fetched request/response, with no independent lifecycle to justify its own class.

- **No API key on this call.** `GetNumberOfCurrentPlayers` is verified to work key-less
  (`{"response":{"player_count":1206380,"result":1}}` for a valid appid,
  `{"response":{"result":42}}` for an invalid one — no error, no exception, just an absent field).
  `SteamApi`'s method signature reflects this: `getNumberOfCurrentPlayers(appId: Long)`, no `key`
  parameter, unlike every sibling method in that interface.
  *Why:* matches Steam's actual contract; adding an unused `key` parameter would misrepresent the
  call and invite a spurious `CredentialsRepository` dependency where none is needed.

- **Never persisted; discarded on leaving the screen.** Held only in `GameDetailViewModel`'s
  `StateFlow`, re-fetched from scratch on every visit.
  *Why:* consistent with `LiveStatusRepository`'s presence/now-playing values — a stale number
  shown after the screen was last open an hour ago would be actively misleading, not just missing.

- **Absence renders as nothing.** `result != 1` or a network failure both collapse to `null` in
  `GameRepository`, and the UI renders no line at all when `activePlayers == null` — no "0 playing
  now," no dash, no spinner that never resolves.
  *Why:* identical doctrine to the existing HLTB-lengths and achievement-description rendering in
  the same screen (`GameSummarySection`/`HltbLengths`/`AchievementDescription`): omit individual
  facts the app doesn't have rather than rendering a placeholder that reads as broken or as a real
  zero.

- **Fetch failure never blocks or delays the rest of the summary.** The count arrives
  asynchronously after the game/achievement/HLTB data (all local) has already rendered; a slow or
  failed network call cannot hold up anything else on the screen.
  *Why:* matches the existing screen's resilience posture (`HeaderArt`'s independent loading/error
  slots, `PlaytimeLine` rendering from local data immediately).

## Risks / Trade-offs

- **One extra network round-trip per detail-screen visit** → acceptable; it's a single lightweight,
  key-less GET, and a failure degrades to "no line shown," never a broken screen.
- **Number can look stale if the player keeps a screen open a long time** → accepted given the
  one-shot decision; if this proves to matter in practice, a pull-to-refresh (not a poll) is the
  narrower follow-up, deliberately not built here.
- **Steam has no count for some legitimate games** (e.g. very old titles, some non-game apps) →
  by design this reads as "no line," which is honest rather than an error state needing explanation.

## Open Questions

- None outstanding. If usage later shows people expect this to update while the screen stays open,
  revisit polling then rather than building it speculatively now.
