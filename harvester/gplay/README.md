# gplayapi cross-check (server-side, plain JVM)

Queries Google Play for the authoritative testing-program flag and production
version per package, to confirm/supplement the APKMirror source. Runs on a plain
JVM (GitHub Actions, no Android, no emulator) by reusing gplayapi's proven code.

## How it works

`com.auroraoss:gplayapi` is published only as an Android library, but at runtime
it touches just four `android.*` classes (`util.Base64`, `util.Log`, `os.Parcel`,
`os.Parcelable`). We consume its extracted `classes.jar` and supply tiny JVM stubs
for those (`src/main/java/android/…`), plus an explicit device profile
(`src/main/resources/device.properties`) so it doesn't need a Context for res/raw.

`Main.kt`: `AuthHelper.build(email, aasToken, AAS, …)` → `AppDetailsHelper
.getAppByPackageName(pkg)` → `app.testingProgram.isAvailable` + `app.versionCode`.

## Setup (one-time)

The gplayapi jar is GPL and not committed; fetch it from Maven Central:

```bash
curl -sL https://repo1.maven.org/maven2/com/auroraoss/gplayapi/3.4.2/gplayapi-3.4.2.aar -o /tmp/g.aar
mkdir -p libs && unzip -o /tmp/g.aar classes.jar -d libs && mv libs/classes.jar libs/gplayapi-3.4.2.jar
```

Credentials live in `../.gplay.local` (EMAIL, AAS_TOKEN — a master token, gitignored).

## Run

```bash
gradlew -p harvester/gplay run --args="../.gplay.local com.whatsapp com.spotify.music org.telegram.messenger"
```

Output (verified — matches the on-device instrumented probe):

```
com.whatsapp   available=true  subscribed=false  versionCode=262307413  name=WhatsApp Messenger
com.spotify.music  available=true  subscribed=false  versionCode=143403975  name=Spotify: Music and Podcasts
```

## Notes

- `available` = a testing program exists (authoritative). `subscribed` reflects the
  harvester's own account, not any app user. Open-vs-full is **not** exposed here —
  that still needs the opt-in checker.
- Unofficial Google Play API: against Google ToS, use a throwaway account.
- GPL-3.0 component (gplayapi is GPL); isolated from the Apache-2.0 app.
