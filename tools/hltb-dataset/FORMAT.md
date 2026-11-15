# HLTB dataset format

The published dataset is one UTF-8 JSON file named `hltb-dataset.json`. Its SHA-256 release asset
is named `hltb-dataset.json.sha256`. The repository's canonical copy is `dataset.json` in this
directory. Contributions use the same JSON shape and should be named
`backlogium-hltb-contribution.json` when exported by the app.

JSON was chosen over CSV or an archive because it keeps the two relations and their metadata in
one independently verifiable asset. Numeric tuples avoid repeating field names for every row and
make the file compact while preserving a small, explicit schema.

## Schema version 1

The top-level fields and their canonical order are:

```json
{
  "schemaVersion": 1,
  "datasetVersion": 12,
  "gatheredAt": 1788134400000,
  "mappings": [
    [400,7231],
    [620,7231]
  ],
  "lengths": [
    [7231,512,824,1353,null]
  ]
}
```

- `schemaVersion` is the serialization contract. This document defines version `1`.
- `datasetVersion` is the non-negative release sequence mirrored by the
  `hltb-dataset-vN` tag. The empty, unpublished repository baseline uses `0`; a contribution also
  uses `0` because it is not itself a published dataset.
- `gatheredAt` is the non-negative Unix epoch time in milliseconds at which the represented HLTB
  observations were gathered. Zero is reserved for the empty repository baseline. A file with any
  row must have a positive value. It is deliberately not the time a device imports the file.
- `mappings` is the correspondence relation. Each tuple is `[appId,hltbId]`.
- `lengths` is the HLTB content relation. Each tuple is
  `[hltbId,mainStoryMinutes,mainExtraMinutes,completionistMinutes,allStylesMinutes]`.

The relations are independent. Several app ids may map to one HLTB id, while its lengths appear
only once. A mapping may refer to an HLTB id absent from `lengths`; that means the correspondence
is known but all four lengths are unknown. Individual length cells may be `null`. A tuple with all
four cells `null` is omitted instead of being written to `lengths`.

The only permitted top-level values are the five fields above. Rows have the exact tuple arity
shown above. This excludes playtime, sessions, achievements, account identifiers, names, and all
other personal or descriptive values by construction.

## Numeric constraints

- App ids and HLTB ids are positive JSON safe integers (1 through 9,007,199,254,740,991).
- A known length is an integer number of minutes from `0` through `600000`, inclusive.
- `null` is the only representation of an unknown length.
- An app id occurs at most once in `mappings`; an HLTB id occurs at most once in `lengths`.
- Every `lengths` HLTB id is referenced by at least one mapping in the same file.

The `600000`-minute ceiling is 10,000 hours (about 417 days of continuous play). It accommodates
exceptionally long real-time and open-ended games while rejecting implausible values and common
unit mistakes such as storing seconds as minutes.

## Canonical serialization

`merge.mjs` is the canonical serializer. It emits:

- the five top-level fields in the order shown above;
- mappings sorted numerically by `appId`;
- lengths sorted numerically by `hltbId`;
- one compact tuple per line, two-space structural indentation, and no trailing commas;
- UTF-8 text with LF line endings and exactly one final newline.

Merging a contribution adds new mappings. A different HLTB id for an existing app id is a blocking
correspondence conflict and both values are reported. For a shared HLTB id, the length tuple from
the greatest `gatheredAt` wins. If timestamps tie, the lexicographically greater canonical tuple is
used as a deterministic tie-break; contribution argument order never decides the value.

When the relations change, the tool increments `datasetVersion` once and advances `gatheredAt` to
the newest observation that contributed a changed row. A fully redundant merge preserves every
byte, including metadata.

The hand-written, canonical two-row example is
[`test/fixtures/sample-two-row.json`](test/fixtures/sample-two-row.json).

## Partial refresh freshness limitation

Schema v1 has one global `gatheredAt`; it cannot encode an independent observation time for
each length tuple. A selected-library refresh merges only genuinely observed usable tuples
(with their original observation times) and reviewed correspondence additions. Failed or
all-unknown reads retain existing rows and never resubmit retained values as fresh observations.
The merge increments the dataset version at most once, preserves redundant bytes, and keeps
its existing timestamp precedence, including rejecting older tuple observations.

When another tuple changes, the global timestamp can advance although many rows were never
fetched. Existing consumers can consequently assign that age to every imported row. This
metadata is not evidence of a full-catalog or full-library refresh. The refresh tool keeps
separate private successful-fetch evidence for stale selection and reports actual checked,
changed, failed and untouched scope. Per-row ages in the published file and Android consumers
would require a separate schema/consumer change.

## Private review format version 1

This separate format never changes public schema v1. Keep artifacts beneath ignored
`tools/hltb-dataset/.local/discovery/<opaque-id>/review.json`; they disclose owned app IDs
and human decisions even without account attribution. Do not publish them.

The exact fields are `reviewVersion: 1`, `discoveryId` (32 lowercase hex),
`baselineHash` and `scopeHash` (SHA-256 lowercase hex), `createdAt` (positive epoch
milliseconds, discovery metadata only), `libraries` (`requested`, `readable`,
`unavailable`, `empty` nonnegative counts), `requests` (`attempts`, `retries`,
`elapsedMs` nonnegative integers), `warning`, and `apps`.
The warning is exactly: “Search previews and discovery times are not direct-page
observations or successful-fetch times.”

Each app has `appId` (positive safe integer), `steamTitle` (sanitized text or null),
`outcome` (`candidates`, `unmatched`, `failed`, `unattempted`), `category` (null for
successful search, an allowlisted search failure otherwise; candidate-bearing
apps may retain a failure category for a partially failed broader search), and `candidates`.
Only `candidates` outcomes have nonempty candidates. Each candidate has `hltbId`
(positive safe integer), `hltbTitle` (sanitized text or null), `previewMinutes`
(exactly four integer minutes 0–600000 or null, in public style order), `source`
(`primary` or `broader`), `query` (sanitized nonempty text), `score` (0–1 inclusive),
and `decision` (`pending`, `approved`, `rejected`). Duplicate app or per-app candidate
IDs and multiple approvals for one app are invalid. Unknown fields are rejected.

A person edits only `decision`, approving or rejecting exact app/HLTB pairs.
Exact titles and scores never grant approval. Search outcomes remain independent
of decisions. Preview lengths and creation times are never final fetch evidence.
Secrets, account attribution, session state, raw responses and instructions belong
in none of these fields. `review.mjs` defines the failure-category allowlist.

Review destinations are created atomically with private permissions and must not
already exist. Default discovery files live under `.local/discovery/<id>/`;
default fetch state lives in `.local/runs/` and `.local/history.json`. Fetch can
read a review under that default private root without writing it. Custom layouts
should use sibling `.local/reviews/`, `.local/fetch-state/` and `.local/outputs/`
directories. Never place a review beneath either state's `runs/`, at `history.json`,
or at an alias of an input/output/history/checkpoint. Existing decisions and all
input bytes survive refusal. Discovery recovery uses a fresh destination; it does
not resume or reset a prior review.
