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

**pt-BR:** Encontre, participe e acompanhe os betas do Google Play dos seus apps.

**es:** Encuentra, únete y sigue las betas de Google Play de tus apps instaladas.

**de:** Finde, tritt bei und beobachte Google-Play-Betas deiner installierten Apps.

**fr:** Trouvez, rejoignez et suivez les bêtas Google Play de vos applis installées.

## Full description

**en:**

> **BetaScout finds the beta programs hiding in your app list.**
>
> It scans your installed apps, shows which ones have a Google Play testing
> program, and tells you in plain words what that means for you: *Beta open —
> you can join*, *Beta is full*, or *You're in the beta*.
>
> - **Open betas first** — a section at the top of the front page lists the
>   programs you could join right now, one tap from the opt-in page.
> - **Watch full betas** — get a notification when a watched program starts
>   accepting testers again (checked every few hours).
> - **Sign in, optionally** — with your Google account, BetaScout reads your
>   own testing pages to detect the memberships you already have. Gentle,
>   rate-limited, and entirely optional.
> - **Grow the catalog together** — an opt-in (off by default) lets your scans
>   anonymously contribute newly found programs to the shared catalog. Only
>   bare package names are ever sent.
> - **Private by design** — no ads, no analytics, no telemetry. Your app list
>   and memberships stay on your device — the only exception is the opt-in
>   catalog sharing above, which sends bare package names only. Open source
>   under Apache 2.0.
>
> Note: reading your own testing pages automatically is a gray area in
> Google's terms; BetaScout is deliberately gentle, but a small account risk
> cannot be ruled out. Without signing in you can still browse found betas,
> open their opt-in pages and set reminders — live open/full status, watch
> notifications and membership detection need the optional sign-in.

**fi:**

> **BetaScout löytää sovelluslistaasi piiloutuneet beta-ohjelmat.**
>
> Se skannaa asennetut sovelluksesi, kertoo mille niistä on Google Play
> -testiohjelma ja sanoo selkokielellä mitä se sinulle tarkoittaa: *Beta auki —
> voit liittyä*, *Beta on täynnä* tai *Olet mukana betassa*.
>
> - **Avoimet betat ensin** — etusivun nosto listaa ohjelmat joihin voisit
>   liittyä juuri nyt, yhden napautuksen päässä liittymissivusta.
> - **Vahdi täysiä betoja** — saat ilmoituksen kun vahdittu ohjelma alkaa taas
>   ottaa testaajia (tarkistus muutaman tunnin välein).
> - **Kirjaudu halutessasi** — Google-tililläsi BetaScout lukee omat
>   testisivusi ja tunnistaa jäsenyytesi automaattisesti. Maltillisesti,
>   rajoitetulla tahdilla ja täysin vapaaehtoisesti.
> - **Kasvata katalogia yhdessä** — vapaaehtoinen jako (oletuksena pois)
>   lähettää skannaustesi löytämät uudet ohjelmat nimettömästi yhteiseen
>   katalogiin. Vain pakettinimiä, ei koskaan mitään muuta.
> - **Yksityinen lähtökohtaisesti** — ei mainoksia, ei analytiikkaa, ei
>   telemetriaa. Sovelluslistasi ja jäsenyytesi pysyvät laitteellasi — ainoa
>   poikkeus on yllä kuvattu vapaaehtoinen katalogijako, joka lähettää vain
>   paljaat pakettinimet. Avoin lähdekoodi (Apache 2.0).
>
> Huomio: omien testisivujen automaattinen luku on Googlen ehtojen harmaata
> aluetta; BetaScout on tarkoituksella varovainen, mutta pientä tiliriskiä ei
> voi sulkea pois. Kirjautumatta voit silti selata löydettyjä betoja, avata
> liittymissivut ja asettaa muistutuksia — live-tila (auki/täynnä),
> vahti-ilmoitukset ja jäsenyyksien tunnistus vaativat vapaaehtoisen
> kirjautumisen.

**pt-BR:**

> **O BetaScout encontra os programas beta escondidos na sua lista de apps.**
>
> Ele verifica seus apps instalados, mostra quais têm um programa de teste no
> Google Play e explica em palavras simples o que isso significa para você:
> *Beta aberto — você pode participar*, *O beta está lotado* ou *Você está no
> beta*.
>
> - **Betas abertos em primeiro lugar** — uma seção no topo da página inicial
>   lista os programas em que você poderia entrar agora mesmo, a um toque da
>   página de inscrição.
> - **Acompanhe betas lotados** — receba uma notificação quando um programa
>   acompanhado voltar a aceitar testadores (conferido a cada poucas horas).
> - **Faça login, se quiser** — com sua conta do Google, o BetaScout lê suas
>   próprias páginas de teste para detectar as participações que você já tem.
>   Leve, com ritmo limitado e totalmente opcional.
> - **Amplie o catálogo em conjunto** — uma opção (desativada por padrão)
>   permite que suas verificações contribuam anonimamente com programas
>   recém-descobertos para o catálogo compartilhado. Apenas nomes de pacote
>   são enviados, nada mais.
> - **Privado por padrão** — sem anúncios, sem análises, sem telemetria. Sua
>   lista de apps e suas participações ficam no seu dispositivo — a única
>   exceção é o compartilhamento opcional do catálogo descrito acima, que
>   envia somente nomes de pacote. Código aberto sob Apache 2.0.
>
> Observação: a leitura automática das suas próprias páginas de teste é uma
> área cinzenta nos termos do Google; o BetaScout é deliberadamente cuidadoso,
> mas um pequeno risco para a conta não pode ser descartado. Sem login, você
> ainda pode explorar os betas encontrados, abrir as páginas de inscrição e
> criar lembretes — o status ao vivo (aberto/lotado), as notificações de
> acompanhamento e a detecção de participação exigem o login opcional.

**es:**

> **BetaScout encuentra los programas beta escondidos en tu lista de apps.**
>
> Revisa tus apps instaladas, muestra cuáles tienen un programa de pruebas en
> Google Play y te dice en palabras sencillas qué significa para ti: *Beta
> abierta — puedes unirte*, *La beta está completa* o *Estás en la beta*.
>
> - **Betas abiertas primero** — una sección en lo alto de la página principal
>   lista los programas a los que podrías unirte ahora mismo, a un toque de la
>   página de inscripción.
> - **Sigue las betas completas** — recibe una notificación cuando un programa
>   en seguimiento vuelva a aceptar testers (se revisa cada pocas horas).
> - **Inicia sesión, si quieres** — con tu cuenta de Google, BetaScout lee tus
>   propias páginas de pruebas para detectar las participaciones que ya tienes.
>   Suave, con ritmo limitado y totalmente opcional.
> - **Amplía el catálogo en común** — una opción (desactivada por defecto)
>   permite que tus verificaciones aporten anónimamente los programas recién
>   encontrados al catálogo compartido. Solo se envían nombres de paquete,
>   nada más.
> - **Privado por diseño** — sin anuncios, sin analíticas, sin telemetría. Tu
>   lista de apps y tus participaciones se quedan en tu dispositivo — la única
>   excepción es el uso compartido opcional del catálogo descrito arriba, que
>   envía solo nombres de paquete. Código abierto bajo Apache 2.0.
>
> Nota: leer automáticamente tus propias páginas de pruebas es una zona gris
> en las condiciones de Google; BetaScout es deliberadamente cuidadoso, pero
> no se puede descartar un pequeño riesgo para la cuenta. Sin iniciar sesión
> aún puedes explorar las betas encontradas, abrir sus páginas de inscripción
> y crear recordatorios — el estado en vivo (abierta/completa), las
> notificaciones de seguimiento y la detección de participaciones requieren el
> inicio de sesión opcional.

**de:**

> **BetaScout findet die Beta-Programme, die sich in deiner App-Liste
> verstecken.**
>
> Es scannt deine installierten Apps, zeigt, welche ein
> Google-Play-Testprogramm haben, und sagt dir in klaren Worten, was das für
> dich heißt: *Beta offen — du kannst beitreten*, *Die Beta ist voll* oder
> *Du bist in der Beta*.
>
> - **Offene Betas zuerst** — ein Bereich oben auf der Startseite listet die
>   Programme, denen du sofort beitreten könntest, einen Tipp von der
>   Anmeldeseite entfernt.
> - **Volle Betas beobachten** — erhalte eine Benachrichtigung, wenn ein
>   beobachtetes Programm wieder Tester aufnimmt (alle paar Stunden geprüft).
> - **Anmelden, wenn du willst** — mit deinem Google-Konto liest BetaScout
>   deine eigenen Testseiten und erkennt die Teilnahmen, die du schon hast.
>   Sanft, gedrosselt und komplett optional.
> - **Den Katalog gemeinsam erweitern** — eine Option (standardmäßig aus)
>   lässt deine Scans neu gefundene Programme anonym zum gemeinsamen Katalog
>   beitragen. Es werden nur bloße Paketnamen gesendet, sonst nichts.
> - **Privat von Grund auf** — keine Werbung, keine Analyse, keine Telemetrie.
>   Deine App-Liste und deine Teilnahmen bleiben auf deinem Gerät — die
>   einzige Ausnahme ist das optionale Katalog-Teilen oben, das nur bloße
>   Paketnamen sendet. Open Source unter Apache 2.0.
>
> Hinweis: Das automatische Lesen deiner eigenen Testseiten ist eine Grauzone
> in den Google-Bedingungen; BetaScout ist bewusst zurückhaltend, aber ein
> kleines Kontorisiko lässt sich nicht ausschließen. Ohne Anmeldung kannst du
> trotzdem gefundene Betas durchstöbern, ihre Anmeldeseiten öffnen und
> Erinnerungen setzen — Live-Status (offen/voll),
> Beobachtungs-Benachrichtigungen und Teilnahme-Erkennung brauchen die
> optionale Anmeldung.

**fr:**

> **BetaScout trouve les programmes bêta cachés dans votre liste d'applis.**
>
> Il analyse vos applis installées, montre lesquelles ont un programme de
> test sur Google Play et vous dit en termes simples ce que cela signifie
> pour vous : *Bêta ouverte — vous pouvez la rejoindre*, *La bêta est
> complète* ou *Vous êtes dans la bêta*.
>
> - **Les bêtas ouvertes d'abord** — une section en haut de la page d'accueil
>   liste les programmes que vous pourriez rejoindre tout de suite, à un
>   geste de la page d'inscription.
> - **Suivez les bêtas complètes** — recevez une notification quand un
>   programme suivi accepte de nouveau des testeurs (vérifié à intervalles de
>   quelques heures).
> - **Connectez-vous, si vous voulez** — avec votre compte Google, BetaScout
>   lit vos propres pages de test pour détecter les participations que vous
>   avez déjà. En douceur, à rythme limité et entièrement facultatif.
> - **Enrichissez le catalogue ensemble** — une option (désactivée par
>   défaut) permet à vos analyses d'apporter anonymement les programmes
>   nouvellement trouvés au catalogue partagé. Seuls des noms de paquet sont
>   envoyés, rien d'autre.
> - **Privé par conception** — pas de pub, pas d'analytique, pas de
>   télémétrie. Votre liste d'applis et vos participations restent sur votre
>   appareil — la seule exception est le partage facultatif du catalogue
>   décrit ci-dessus, qui n'envoie que de simples noms de paquet. Open source
>   sous licence Apache 2.0.
>
> Remarque : la lecture automatique de vos propres pages de test est une zone
> grise dans les conditions de Google ; BetaScout est volontairement prudent,
> mais un petit risque pour le compte ne peut pas être exclu. Sans connexion,
> vous pouvez quand même parcourir les bêtas trouvées, ouvrir leurs pages
> d'inscription et créer des rappels — le statut en direct (ouverte/complète),
> les notifications de suivi et la détection des participations nécessitent
> la connexion facultative.

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
