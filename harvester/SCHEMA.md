# Beta catalog schema (v2)

The catalog is the contract between the harvester and the Android app. The app
parses it with `BetaSeedParser`, which ignores unknown keys, so extra provenance
fields are safe to include.

```jsonc
{
  "version": 2,
  "generatedAt": 1783253888947,      // epoch millis the catalog was produced
  "programs": [
    {
      "packageName": "org.telegram.messenger.beta", // required, the match key
      "appName": "Telegram Beta",                    // display name
      "testingUrl": null,                            // optional; app derives it from the package if absent
      "knownStatus": "UNKNOWN",                      // static heuristic: UNKNOWN | OFTEN_OPEN | OFTEN_FULL | NO_PROGRAM
      "liveStatus": "UNKNOWN",                       // freshly observed: UNKNOWN | OPEN | FULL | CLOSED
      "statusCheckedAt": null,                       // epoch millis liveStatus was verified; null if never
      "notes": "Beta build seen on APKMirror",

      // Provenance (ignored by the app, used by the harvester's merge step):
      "hasBeta": true,
      "source": "APKMIRROR"                          // APKMIRROR | GPLAYAPI | CROWD | OPTIN | CURATED
    }
  ]
}
```

## Field ownership by source

| Field | Filled by |
|-------|-----------|
| `packageName`, `appName`, `hasBeta` | APKMirror harvest, gplayapi, crowd hints (gplayapi-verified), or curation |
| `liveStatus`, `statusCheckedAt` | the opt-in open/full checker (authenticated) |
| `knownStatus`, `notes` | curation |

`liveStatus` stays `UNKNOWN` until the opt-in checker runs — APKMirror only tells
us a beta build exists, not whether the Play testing program is open or full.
