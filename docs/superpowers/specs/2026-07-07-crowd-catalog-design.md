# Crowd-sourced catalog growth — design

**Date:** 2026-07-07
**Status:** Approved design, not yet implemented
**Depends on:** `feat/scraping-hybrid` merged to main (needs the `beta_observations` model
and a device-validated scan pipeline). Implement on a new branch (`feat/crowd-catalog`).

## Goal

Let users who opt in contribute *account-neutral* discovery facts — "package X has a Play
testing program" — to the shared catalog, so the catalog grows with real-world usage instead
of relying only on the APKMirror feed and a curated seed list.

## Non-goals

- No live-status (open/full/closed) aggregation across users. Discovery only.
- No browse-view for betas of apps the user does not have installed (separate spec).
- No submitter identifiers of any kind — no account, device, install or session IDs.
- No admin tooling or moderation UI.

## Background

Today the catalog (25 programs) is produced by the harvester (`harvester/`, Node, daily
GitHub Actions run): APKMirror discovery + gplayapi verification with a throwaway account →
`catalog/catalog.json` → pushed to Cloudflare KV → served read-only by the
`betascout-catalog` Worker (`cloudflare/catalog-worker/`). The app fetches it via
`CatalogProvider` (remote → cache → bundled). Scans on devices already observe, per
installed app, whether a testing program exists — that knowledge currently stays on the
device.

## Architecture

```
App ──POST /hints {packages:[...]}──▶ Worker ──KV: hint:<pkg>──▶ Harvester (daily)
                                                                   │ gplayapi verification
App ◀──GET / (catalog) ◀── KV: catalog ◀── merge + KV push ◀───────┘ source: "CROWD"
```

Poisoning protection comes from verification, not trust: hints are only *candidates*.
Nothing enters the published catalog until the harvester's gplayapi check confirms the
package has a testing program. Because hints are worthless without verification, the
endpoint needs no submitter identity, which is what makes the zero-identifier privacy
model possible.

## Component 1: App — consent and upload

**Consent.** New DataStore boolean `shareDiscoveries`, default **false**, plus
`sharePromptShown`. After the first successful scan, the account screen shows a one-time
card: "Help grow the beta catalog — anonymously share which testing programs your scans
find" with Enable / No thanks. Either choice dismisses the card permanently; the setting
remains toggleable on the account screen. All strings in `values/` and `values-fi/`.

**Upload set.** After every completed scan (manual and BetaScanWorker), when the setting is
on, upload the packages that satisfy all of:

1. The current account's latest observation for the package has
   `liveStatus ∈ {OPEN, FULL, CLOSED}` (i.e. the scan actually saw a program page).
   Membership is never read for this decision and never uploaded; a JOINED observation
   contributes only because its liveStatus is OPEN.
2. The package is not already in the remote catalog (`beta_programs` rows that came from
   the catalog, not user-created rows).
3. The package is not in the local reported-set (DataStore string set
   `reported_packages`), which is updated **only after a successful (2xx) upload** — a
   failed upload retries naturally after the next scan.

**Transport.** `POST <worker>/hints` with body `{"version":1,"packages":["com.x", ...]}` —
nothing else. `HttpURLConnection` like `CatalogProvider`, no new dependencies.
Fire-and-forget: any error is logged (tag `BetaScout`) and swallowed; the upload must never
affect scan UX or results. A `DiscoveryReporter` class in the data layer owns selection +
transport; callers invoke it after a scan completes.

## Component 2: Worker — `POST /hints`

Extends the existing `betascout-catalog` Worker (same KV binding).

- **Validation:** body is JSON with `packages` array, ≤ 50 entries per request; each entry
  matches `^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$` and is ≤ 150 chars.
  Malformed body → 400; too many entries → 413.
- **Filtering:** packages already present in the published catalog are ignored.
- **Storage:** KV `hint:<package>` = `{"firstSeen": <ms>, "count": <n>}`. Count is
  incremented best-effort (read-modify-write; races are acceptable — count is
  informational only, never a trust signal).
- **Rate limiting:** Workers rate-limiting binding keyed by client IP (in-memory; the IP
  is never persisted). On limit → 429. If the binding is unavailable, ship without it —
  verification already caps the damage of abuse.
- **Response:** 204 on accept, regardless of how many entries were stored (no feedback
  channel for probing the catalog).
- **Harvester endpoints:** `GET /hints` (returns
  `{"hints":[{"packageName":"com.x","firstSeen":0,"count":1}, ...]}`) and
  `POST /hints/consume {packages:[...]}` (delete processed hints), both requiring
  `Authorization: Bearer <HINTS_TOKEN>` (Worker secret; mirrored as a GitHub Actions
  secret). Raw hints are not publicly readable.

## Component 3: Harvester — verification

New step in the daily run, after the existing APKMirror harvest:

1. `GET /hints` with the token. On failure, skip the step (the normal harvest is
   unaffected).
2. Drop hints that are already in the catalog or on the rejected list
   (`harvester/hints-rejected.json`, committed like the catalog; entries carry a
   timestamp and are re-eligible after 30 days).
3. Verify at most **25 new packages per run** (env `GPLAY_HINTS_CAP`, protects the
   throwaway account) with the existing gplayapi JVM tool.
4. `hasBeta=true` → merge into the catalog with `source: "CROWD"`, `appName` and
   `productionVersionCode` from gplayapi. `hasBeta=false` → add to the rejected list.
   Per-package gplayapi errors leave the hint pending for the next run.
5. Push the merged catalog to KV (existing step) and `POST /hints/consume` for every
   hint that was either merged or rejected.

Worst-case abuse: an attacker can waste 25 gplayapi lookups per day; nothing unverified
can reach the catalog.

## Schema change

`harvester/SCHEMA.md` source vocabulary gains `CROWD`. The app's `BetaSeedParser` already
tolerates unknown source values, so no app-side schema work is needed.

## Privacy model

- Device → server: package names only, and only the subset with an observed testing
  program. Never membership, account, email, device or install identifiers. TLS.
- Server stores: package name, first-seen timestamp, count. No IPs (rate limiting is
  memory-only), no request logging of bodies.
- Sharing is opt-in, default off, with a plain-language prompt. Document the model in the
  README (privacy section) when the feature ships.

## Error handling

| Layer | Failure | Behavior |
|---|---|---|
| App | Upload IOException / non-2xx | Log + skip; reported-set not updated → retried after next scan |
| Worker | Invalid body / oversized / rate-limited | 400 / 413 / 429; valid entries in a partially-invalid batch are not stored (reject whole request) |
| Harvester | Hints fetch fails | Skip step, normal harvest proceeds |
| Harvester | gplayapi error on one package | Hint stays pending for next run |

## Testing

- **App (TDD):** upload-set selection (liveStatus filter, catalog exclusion, reported-set
  semantics incl. update-only-on-success), consent state machine (default off, prompt
  shown once, toggle honored).
- **Worker:** validation and filtering as a pure function with `node --test` (same style
  as the harvester tests).
- **Harvester (TDD):** hints → verification → merge/rejected flow, cap enforcement,
  30-day rejected re-eligibility.
- **E2E (manual):** device with sharing on → hint visible in KV → local harvester run →
  catalog contains the package with `source: "CROWD"` → app fetches it.

## Rollout

1. Implement on `feat/crowd-catalog` after `feat/scraping-hybrid` merges.
2. Worker deploy and the `HINTS_TOKEN` secret require the owner's approval (public
   deploy), as before.
3. GitHub Actions: add `HINTS_TOKEN` secret; no schedule change.
