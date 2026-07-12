# Store listing material (Aptoide and friends)

Everything a store listing needs, in both languages. Screenshots live in
`store-assets/` (not committed — regenerate any time; see the checklist at the
bottom).

## Basics

| Field | Value |
|---|---|
| App name | BetaScout |
| Package | `org.jarsi.betascout` |
| Category | Tools |
| Price | Free, no ads, no in-app purchases |
| License | Apache 2.0 (open source) |
| Website | https://github.com/jrs8205/BetaScout |
| Privacy policy | https://github.com/jrs8205/BetaScout/blob/main/PRIVACY.md |
| Support | https://github.com/jrs8205/BetaScout/issues |
| Icon | `app/src/main/res/mipmap-*/ic_launcher*` (from the repo build) |

## Short description (max ~80 chars)

**en:** Find, join and watch Google Play beta programs for your installed apps.

**fi:** Löydä, liity ja vahdi asennettujen sovellustesi Google Play -betoja.

## Full description

**en:**

> **BetaScout finds the beta programs hiding in your app list.**
>
> It scans your installed apps, shows which ones have a Google Play testing
> program, and tells you in plain words what that means for you: *Beta open —
> you can join*, *Beta is full*, or *You're in the beta*.
>
> - **Open betas first** — a rail on the front page lists the programs you
>   could join right now, one tap from the opt-in page.
> - **Watch full betas** — get a notification the moment a watched program
>   starts accepting testers again.
> - **Sign in, optionally** — with your Google account, BetaScout reads your
>   own testing pages to detect the memberships you already have. Gentle,
>   rate-limited, and entirely optional.
> - **Grow the catalog together** — an opt-in (off by default) lets your scans
>   anonymously contribute newly found programs to the shared catalog. Only
>   bare package names are ever sent.
> - **Private by design** — no ads, no analytics, no telemetry. Your app list
>   and memberships never leave your device. Open source under Apache 2.0.
>
> Note: reading your own testing pages automatically is a gray area in
> Google's terms; BetaScout is deliberately gentle, but a small account risk
> cannot be ruled out. Everything except membership detection works without
> signing in.

**fi:**

> **BetaScout löytää sovelluslistaasi piiloutuneet beta-ohjelmat.**
>
> Se skannaa asennetut sovelluksesi, kertoo mille niistä on Google Play
> -testiohjelma ja sanoo selkokielellä mitä se sinulle tarkoittaa: *Beta auki —
> voit liittyä*, *Beta on täynnä* tai *Olet mukana betassa*.
>
> - **Avoimet betat ensin** — etusivun nosto listaa ohjelmat joihin voisit
>   liittyä juuri nyt, yhden napautuksen päässä liittymissivusta.
> - **Vahdi täysiä betoja** — saat ilmoituksen heti kun vahdittu ohjelma alkaa
>   taas ottaa testaajia.
> - **Kirjaudu halutessasi** — Google-tililläsi BetaScout lukee omat
>   testisivusi ja tunnistaa jäsenyytesi automaattisesti. Maltillisesti,
>   rajoitetulla tahdilla ja täysin vapaaehtoisesti.
> - **Kasvata katalogia yhdessä** — vapaaehtoinen jako (oletuksena pois)
>   lähettää skannaustesi löytämät uudet ohjelmat nimettömästi yhteiseen
>   katalogiin. Vain pakettinimiä, ei koskaan mitään muuta.
> - **Yksityinen lähtökohtaisesti** — ei mainoksia, ei analytiikkaa, ei
>   telemetriaa. Sovelluslistasi ja jäsenyytesi eivät poistu laitteeltasi.
>   Avoin lähdekoodi (Apache 2.0).
>
> Huomio: omien testisivujen automaattinen luku on Googlen ehtojen harmaata
> aluetta; BetaScout on tarkoituksella varovainen, mutta pientä tiliriskiä ei
> voi sulkea pois. Kaikki muu paitsi jäsenyyksien tunnistus toimii
> kirjautumatta.

## Screenshot checklist (`store-assets/`, 1080×2400 PNG)

1. `01-main.png` — front page: open-betas rail + tabs with counts + app rows
2. `02-detail-open.png` — detail screen with the green "Beta is open" hero
3. `03-detail-full.png` — detail screen of a full program (watch suggestion)
4. `04-watchlist.png` — watchlist with a couple of watched apps
5. `05-settings.png` — settings screen (crop or avoid the account email!)

Capture: `adb exec-out screencap -p > store-assets/01-main.png` with the app
in the dark theme (brand look). Do not commit shots that show a personal
email address.

## Release channel notes

- Upload the **release APK from the GitHub release** (same signing key every
  time — the key lives in `betascout-release.keystore`, backed up).
- Aptoide: developer console upload; the anti-malware scan may take a while
  before the "Trusted" badge appears.
- Versioning: store versions must follow the GitHub releases (versionCode
  monotonically increasing).
