# Crowd-Sourced Catalog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans.
> Executed inline by the session that wrote it; the approved spec
> (`docs/superpowers/specs/2026-07-07-crowd-catalog-design.md`) is the authority
> for all behavior; this plan pins interfaces and files.

**Goal:** Opt-in users contribute account-neutral "package X has a testing program" hints; the Worker stores them as candidates; the daily harvester verifies them via gplayapi and grows the published catalog with `source: "CROWD"`.

**Architecture:** App-side `DiscoveryReporter` (pure selection + fire-and-forget POST) invoked after every completed scan; the existing `betascout-catalog` Worker gains `/hints` routes (public POST, token-gated GET/consume); the harvester gains a hints step after the normal harvest. Poisoning protection = gplayapi verification, so no submitter identity anywhere.

**Tech Stack:** Kotlin (app, TDD JUnit4), Cloudflare Worker JS + `node --test`, Node harvester + `node --test`.

## Global Constraints

- Zero submitter identifiers: package names only in `POST /hints` body `{"version":1,"packages":[...]}`.
- Consent default **false**; one-time prompt card; toggle stays on the settings screen; strings en+fi.
- Reported-set updated **only after a 2xx upload**.
- Worker validation: ≤ 50 entries/request, each `^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$` and ≤ 150 chars; 400/413 reject whole request; POST /hints answers 204 always (no probe channel).
- Harvester: `GPLAY_HINTS_CAP` (default 25)/run; rejected list `harvester/hints-rejected.json` with 30-day re-eligibility.
- Upload must never affect scan UX (log tag `BetaScout`, swallow errors).
- Worker deploy + `HINTS_TOKEN` secrets (Worker + GitHub) require the owner's approval — flagged steps at the end.
- Repo 100% English; no attribution trailers; commit per task.

---

### Task 1: Branch
`git checkout -b feat/crowd-catalog` (main synced first).

### Task 2: Worker hints module (TDD, pure) + routing
- Create: `cloudflare/catalog-worker/src/hints.js` — pure functions:
  - `parseHintRequest(bodyText, catalogPackages: Set<string>) → {status: 204|400|413, accepted: string[]}` implementing validation + catalog filtering per Global Constraints.
  - `PACKAGE_RE` exported for reuse.
- Create: `cloudflare/catalog-worker/test/hints.test.js` (`node --test`): valid batch accepted; catalog members filtered out; >50 → 413; bad JSON/missing array/bad name/overlong name → 400; empty accepted list still 204.
- Modify: `cloudflare/catalog-worker/src/index.js` — router:
  - `GET|HEAD /` → existing catalog serving (unchanged).
  - `POST /hints` → `parseHintRequest(body, catalogPackages from KV catalog)`; for each accepted pkg read-modify-write KV `hint:<pkg>` = `{"firstSeen": ms, "count": n}`; respond 204/400/413. No rate-limit binding (spec: ship without it).
  - `GET /hints` + `POST /hints/consume` → require `Authorization: Bearer <env.HINTS_TOKEN>` (401 otherwise; 503 if the secret is unset). GET lists via `env.CATALOG.list({prefix:'hint:'})` → `{"hints":[{packageName, firstSeen, count}]}`; consume deletes `hint:<pkg>` for each posted package, 204.
- Verify: `node --test cloudflare/catalog-worker/test/`. Commit: `Add hint intake and harvester endpoints to the catalog worker`.

### Task 3: Harvester hints step (TDD)
- Create: `harvester/src/hints.js`:
  - `selectHintsToVerify(hints, {catalogPackages, rejected, now, cap}) → {verify: string[], skipped: string[]}` — drops catalog members and rejected entries newer than 30 days (`REJECTED_TTL_MS = 30*24*3600*1000`), caps at `cap`.
  - `updateRejected(rejected, failures, now)` — adds `{packageName, rejectedAt}` entries, keeps existing.
  - `crowdEntry(gplayResult) → catalog entry` with `source: 'CROWD'`, `notes: 'Reported by users, confirmed via Google Play'`, `hasBeta`, `appName`, `productionVersionCode`, `liveStatus: 'UNKNOWN'`.
  - `fetchHints(url, token, fetchImpl)` / `consumeHints(url, token, packages, fetchImpl)` — thin, injectable transport.
- Create: `harvester/test/hints.test.js`: selection (catalog/rejected/30-day re-eligibility/cap), updateRejected, crowdEntry mapping (available=false → hasBeta=false → lands on rejected path in run).
- Modify: `harvester/src/run.js` — after the existing publish-merge computation: when `HINTS_URL` + `HINTS_TOKEN` env set, fetch hints, select, verify via existing `runGplay` (respecting `GPLAY_HINTS_CAP` default 25), merge verified `hasBeta=true` as CROWD entries into the accumulation, append failures to `harvester/hints-rejected.json`, `consumeHints` for merged+rejected (pending errors stay). Any hints-step failure logs and leaves the normal harvest output intact.
- Modify: `harvester/SCHEMA.md` — add `CROWD` to the source vocabulary.
- Verify: `node --test harvester/test/`. Commit: `Verify crowd hints in the harvester and merge them as CROWD entries`.

### Task 4: Workflow wiring
- Modify: `.github/workflows/harvest.yml` — Harvest step env gains `HINTS_URL: https://betascout-catalog.jarsi.workers.dev/hints` and `HINTS_TOKEN: ${{ secrets.HINTS_TOKEN }}` (run.js already tolerates absence). Commit: `Feed crowd hints into the scheduled harvest`.

### Task 5: App consent settings
- Modify: `data/settings/SettingsRepository.kt` — `shareDiscoveries: Flow<Boolean>` (default false) + `setShareDiscoveries`, `sharePromptShown: Flow<Boolean>` + `setSharePromptShown`, `reportedPackages: Flow<Set<String>>` (stringSetPreferencesKey, default empty) + `addReportedPackages(packages: Set<String>)`.
- Commit with Task 6 (no standalone testable unit — exercised through DiscoveryReporter fakes and UI).

### Task 6: DiscoveryReporter (TDD)
- Create: `data/crowd/DiscoveryReporter.kt`:
  ```kotlin
  class DiscoveryReporter(
      private val settings: SettingsRepository,
      private val betaObservationDao: BetaObservationDao,
      private val betaProgramDao: BetaProgramDao,
      private val post: suspend (List<String>) -> Boolean, // true on 2xx
      private val io: CoroutineDispatcher,
  ) {
      suspend fun reportAfterScan(accountKey: String)
  }
  ```
  Selection per spec: account observations with `liveStatus ∈ {OPEN, FULL, CLOSED}`, minus packages with a `beta_programs` row whose `source != USER`, minus `reportedPackages`; no-op when `shareDiscoveries` is false or the set is empty; on `post` returning true, `addReportedPackages`; all exceptions caught and logged (`BetaScout`), never rethrown. Also expose the pure selector as a top-level `fun selectDiscoveries(observations, catalogSourcedPackages, reported): List<String>` for direct tests.
- Create: `test/.../data/crowd/DiscoveryReporterTest.kt` — fakes in the DefaultAppRepositoryTest style: selection filters (liveStatus, catalog exclusion incl. USER rows NOT excluding, reported-set), opt-out no-op, reported-set updated only on success, failure keeps set and swallows.
- Create transport in `di/AppModule.kt`: provide `DiscoveryReporter` with a `HttpURLConnection` POST to `"$CATALOG_URL/hints"` writing `{"version":1,"packages":[...]}` (kotlinx.serialization like the rest), 10 s timeouts, `Boolean` = code in 200..299.
- Modify: `work/BetaScanWorker.kt` — after a successful scan (`summary` obtained, not needs-login), `discoveryReporter.reportAfterScan(session.accountKey)` in a runCatching (both modes).
- Verify: full app suite. Commit: `Report discovered testing programs after scans when sharing is enabled`.

### Task 7: Consent UI
- Modify: `ui/account/AccountViewModel.kt` — expose `shareDiscoveries`, `sharePromptShown` StateFlows + `setShareDiscoveries(Boolean)` (also stamps promptShown), `dismissSharePrompt()`.
- Modify: `ui/account/AccountScreen.kt` — one-time prompt card (visible when signed in ∧ `lastScan != null` ∧ `!sharePromptShown`): title + body + Enable/No-thanks buttons; plus a permanent "Sharing" row with a Switch in the settings list (near the appearance card).
- Strings en+fi: `share_prompt_title` ("Help grow the beta catalog"/"Auta kasvattamaan beta-katalogia"), `share_prompt_body` ("Anonymously share which testing programs your scans find. Only package names are sent — never your account or memberships."/"Jaa nimettömästi, mille sovelluksille skannauksesi löytävät testiohjelman. Vain pakettinimet lähetetään — ei koskaan tiliäsi tai jäsenyyksiäsi."), `share_prompt_enable` ("Enable"/"Ota käyttöön"), `share_prompt_no` ("No thanks"/"Ei kiitos"), `share_toggle_title` ("Share discoveries"/"Jaa löydöt"), `share_toggle_hint` ("Anonymous package names only"/"Vain nimettömät pakettinimet").
- Verify: suite + lint + assembleDebug. Commit: `Add the opt-in card and toggle for sharing discoveries`.

### Task 8: Docs + verification + flagged deploys
- Modify: `README.md` — privacy section paragraph on opt-in discovery sharing (what is/never sent).
- Full verification: app suite + lint + assembleDebug; `node --test` both suites.
- **Owner-approval steps (do NOT run unopposed):** `wrangler deploy` in `cloudflare/catalog-worker`; `wrangler secret put HINTS_TOKEN` (generate a random token); `gh secret set HINTS_TOKEN`. Then E2E per spec: device (sharing on) → scan → KV hint → local harvester run w/ HINTS env → catalog gains CROWD entry → app fetches.
- Merge decision after E2E.
