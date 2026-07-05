# gplayapi cross-check (WIP — blocked on runtime)

Queries Google Play for the authoritative `testingProgramAvailable` flag per
package, to confirm/supplement the APKMirror source. The query logic in
`src/main/kotlin/Main.kt` is correct (`AuthHelper.build(email, aasToken)` →
`AppDetailsHelper.getAppByPackageName(pkg)` → `app.testingProgramAvailable`,
`app.versionCode`).

## What works

- The saved AAS credential (`../.gplay.local`) is valid: it exchanges for a
  Play-scoped auth token (verified by `../tools/verify-play-auth.mjs`).

## The blocker

`com.auroraoss:gplayapi` is published as an **Android library** (`androidJvm`
variant only), so a plain `kotlin("jvm")` project cannot consume it, and it may
reference Android APIs at runtime. Running it standalone/server-side needs one of:

1. **Reimplement the `fdfe/details` call** in Node (or Python) using gplayapi's
   `.proto` + a protobuf library. Pure and server-friendly — the right long-term
   path for the automated harvester.
2. **Run gplayapi inside an Android runtime** (an instrumented test on the
   emulator/device) — quickest way to get a real result now, but not the
   production runtime.
3. **A pure-JVM Play API library** (e.g. an older Java implementation) — simplest
   if one still works against the current protocol.

Decision pending. Credential + auth are proven; only the runtime is open.
