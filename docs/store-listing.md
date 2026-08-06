# Store listing material (Aptoide and friends)

Everything a store listing needs, in all seven app languages. Screenshots live in
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

**it:** Trova, unisciti e segui le beta di Google Play delle tue app installate.

## Full description

Written as console-safe plain text: CAPS section headers, • bullets, no
markdown. Store consoles often strip formatting, so every line stands on its
own and reads fine even if line breaks collapse.

**en:**

> BetaScout finds the beta programs hiding in your app list.
>
> It scans your installed apps and tells you in plain words which ones have a
> Google Play testing program: beta open — you can join, beta full, or you're
> already in.
>
> WHAT IT DOES
> • Open betas first — join with one tap via the official opt-in page.
> • Watch full betas — sign in and get a notification when a watched program
> starts accepting testers again (checked every few hours).
> • Detect your memberships — the optional Google sign-in reads your own
> testing pages and shows live open/full status plus the betas you have
> already joined.
> • Grow the shared catalog — an opt-in (off by default) contributes newly
> found programs anonymously; only bare package names are ever sent.
>
> PRIVACY
> No ads, no analytics, no telemetry. Your app list and memberships stay on
> your device; the only exception is the optional catalog sharing above. Open
> source (Apache 2.0).
>
> LANGUAGES
> English, Spanish, German, French, Italian, Brazilian Portuguese and Finnish.
>
> GOOD TO KNOW
> Without signing in you can still browse found betas, open their opt-in pages
> and set reminders. Automatically reading your own testing pages is a gray
> area in Google's terms; BetaScout is deliberately gentle, but a small
> account risk cannot be ruled out.

**fi:**

> BetaScout löytää sovelluslistaasi piiloutuneet beta-ohjelmat.
>
> Se skannaa asennetut sovelluksesi ja kertoo selkokielellä, mille niistä on
> Google Play -testiohjelma: beta auki — voit liittyä, beta täynnä tai olet
> jo mukana.
>
> MITÄ SE TEKEE
> • Avoimet betat ensin — liity yhdellä napautuksella virallisen
> liittymissivun kautta.
> • Vahdi täysiä betoja — kirjaudu ja saat ilmoituksen, kun vahdittu ohjelma
> alkaa taas ottaa testaajia (tarkistus muutaman tunnin välein).
> • Tunnista jäsenyytesi — vapaaehtoinen Google-kirjautuminen lukee omat
> testisivusi ja näyttää live-tilan (auki/täynnä) sekä betat, joissa jo olet.
> • Kasvata yhteistä katalogia — vapaaehtoinen jako (oletuksena pois)
> lähettää uudet löydöt nimettömästi; vain paljaita pakettinimiä.
>
> YKSITYISYYS
> Ei mainoksia, ei analytiikkaa, ei telemetriaa. Sovelluslistasi ja
> jäsenyytesi pysyvät laitteellasi; ainoa poikkeus on yllä kuvattu
> vapaaehtoinen katalogijako. Avoin lähdekoodi (Apache 2.0).
>
> KIELET
> Englanti, espanja, saksa, ranska, italia, brasilianportugali ja suomi.
>
> HYVÄ TIETÄÄ
> Kirjautumatta voit silti selata löydettyjä betoja, avata liittymissivut ja
> asettaa muistutuksia. Omien testisivujen automaattinen luku on Googlen
> ehtojen harmaata aluetta; BetaScout on tarkoituksella varovainen, mutta
> pientä tiliriskiä ei voi sulkea pois.

**pt-BR:**

> O BetaScout encontra os programas beta escondidos na sua lista de apps.
>
> Ele verifica seus apps instalados e explica em palavras simples quais têm
> um programa de teste no Google Play: beta aberto — você pode participar,
> beta lotado ou você já está dentro.
>
> O QUE ELE FAZ
> • Betas abertos em primeiro lugar — participe com um toque pela página
> oficial de inscrição.
> • Acompanhe betas lotados — faça login e receba uma notificação quando um
> programa acompanhado voltar a aceitar testadores (conferido a cada poucas
> horas).
> • Detecte suas participações — o login opcional com o Google lê suas
> próprias páginas de teste e mostra o status ao vivo (aberto/lotado) e os
> betas em que você já está.
> • Amplie o catálogo compartilhado — uma opção (desativada por padrão)
> contribui anonimamente com os programas recém-descobertos; apenas nomes de
> pacote são enviados.
>
> PRIVACIDADE
> Sem anúncios, sem análises, sem telemetria. Sua lista de apps e suas
> participações ficam no seu dispositivo; a única exceção é o
> compartilhamento opcional do catálogo descrito acima. Código aberto
> (Apache 2.0).
>
> IDIOMAS
> Inglês, espanhol, alemão, francês, italiano, português do Brasil e finlandês.
>
> BOM SABER
> Sem login, você ainda pode explorar os betas encontrados, abrir as páginas
> de inscrição e criar lembretes. A leitura automática das suas próprias
> páginas de teste é uma área cinzenta nos termos do Google; o BetaScout é
> deliberadamente cuidadoso, mas um pequeno risco para a conta não pode ser
> descartado.

**es:**

> BetaScout encuentra los programas beta escondidos en tu lista de apps.
>
> Revisa tus apps instaladas y te dice en palabras sencillas cuáles tienen un
> programa de pruebas en Google Play: beta abierta — puedes unirte, beta
> completa, o ya estás dentro.
>
> QUÉ HACE
> • Betas abiertas primero — únete con un toque desde la página oficial de
> inscripción.
> • Sigue las betas completas — inicia sesión y recibe una notificación
> cuando un programa en seguimiento vuelva a aceptar testers (se revisa cada
> pocas horas).
> • Detecta tus participaciones — el inicio de sesión opcional con Google lee
> tus propias páginas de pruebas y muestra el estado en vivo
> (abierta/completa) y las betas en las que ya estás.
> • Amplía el catálogo compartido — una opción (desactivada por defecto)
> aporta anónimamente los programas recién encontrados; solo se envían
> nombres de paquete.
>
> PRIVACIDAD
> Sin anuncios, sin analíticas, sin telemetría. Tu lista de apps y tus
> participaciones se quedan en tu dispositivo; la única excepción es el uso
> compartido opcional del catálogo descrito arriba. Código abierto
> (Apache 2.0).
>
> IDIOMAS
> Inglés, español, alemán, francés, italiano, portugués de Brasil y finés.
>
> CONVIENE SABER
> Sin iniciar sesión aún puedes explorar las betas encontradas, abrir sus
> páginas de inscripción y crear recordatorios. Leer automáticamente tus
> propias páginas de pruebas es una zona gris en las condiciones de Google;
> BetaScout es deliberadamente cuidadoso, pero no se puede descartar un
> pequeño riesgo para la cuenta.

**de:**

> BetaScout findet die Beta-Programme, die sich in deiner App-Liste
> verstecken.
>
> Es scannt deine installierten Apps und sagt dir in klaren Worten, welche
> ein Google-Play-Testprogramm haben: Beta offen — du kannst beitreten, Beta
> voll oder du bist schon dabei.
>
> WAS ES MACHT
> • Offene Betas zuerst — tritt mit einem Tipp über die offizielle
> Anmeldeseite bei.
> • Volle Betas beobachten — melde dich an und erhalte eine Benachrichtigung,
> wenn ein beobachtetes Programm wieder Tester aufnimmt (alle paar Stunden
> geprüft).
> • Teilnahmen erkennen — die optionale Google-Anmeldung liest deine eigenen
> Testseiten und zeigt den Live-Status (offen/voll) sowie die Betas, in denen
> du schon bist.
> • Den gemeinsamen Katalog erweitern — eine Option (standardmäßig aus)
> trägt neu gefundene Programme anonym bei; gesendet werden nur bloße
> Paketnamen.
>
> PRIVATSPHÄRE
> Keine Werbung, keine Analyse, keine Telemetrie. Deine App-Liste und deine
> Teilnahmen bleiben auf deinem Gerät; die einzige Ausnahme ist das optionale
> Katalog-Teilen oben. Open Source (Apache 2.0).
>
> SPRACHEN
> Englisch, Spanisch, Deutsch, Französisch, Italienisch, brasilianisches
> Portugiesisch und Finnisch.
>
> GUT ZU WISSEN
> Ohne Anmeldung kannst du trotzdem gefundene Betas durchstöbern, ihre
> Anmeldeseiten öffnen und Erinnerungen setzen. Das automatische Lesen deiner
> eigenen Testseiten ist eine Grauzone in den Google-Bedingungen; BetaScout
> ist bewusst zurückhaltend, aber ein kleines Kontorisiko lässt sich nicht
> ausschließen.

**fr:**

> BetaScout trouve les programmes bêta cachés dans votre liste d'applis.
>
> Il analyse vos applis installées et vous dit en termes simples lesquelles
> ont un programme de test sur Google Play : bêta ouverte — vous pouvez la
> rejoindre, bêta complète, ou vous y êtes déjà.
>
> CE QU'IL FAIT
> • Les bêtas ouvertes d'abord — rejoignez-les d'un geste via la page
> d'inscription officielle.
> • Suivez les bêtas complètes — connectez-vous et recevez une notification
> quand un programme suivi accepte de nouveau des testeurs (vérifié à
> intervalles de quelques heures).
> • Détectez vos participations — la connexion facultative avec Google lit
> vos propres pages de test et montre le statut en direct (ouverte/complète)
> ainsi que les bêtas que vous avez déjà rejointes.
> • Enrichissez le catalogue partagé — une option (désactivée par défaut)
> apporte anonymement les programmes nouvellement trouvés ; seuls des noms
> de paquet sont envoyés.
>
> CONFIDENTIALITÉ
> Pas de pub, pas d'analytique, pas de télémétrie. Votre liste d'applis et
> vos participations restent sur votre appareil ; la seule exception est le
> partage facultatif du catalogue décrit ci-dessus. Open source (Apache 2.0).
>
> LANGUES
> Anglais, espagnol, allemand, français, italien, portugais du Brésil et
> finnois.
>
> BON À SAVOIR
> Sans connexion, vous pouvez quand même parcourir les bêtas trouvées, ouvrir
> leurs pages d'inscription et créer des rappels. La lecture automatique de
> vos propres pages de test est une zone grise dans les conditions de
> Google ; BetaScout est volontairement prudent, mais un petit risque pour le
> compte ne peut pas être exclu.

**it:**

> BetaScout trova i programmi beta nascosti nella tua lista di app.
>
> Esamina le app installate e ti dice con parole semplici quali hanno un
> programma di test su Google Play: beta aperta — puoi unirti, beta al
> completo, o sei già dentro.
>
> COSA FA
> • Prima le beta aperte — unisciti con un tocco dalla pagina di iscrizione
> ufficiale.
> • Segui le beta al completo — accedi e ricevi una notifica quando un
> programma seguito ricomincia ad accettare tester (controllato ogni poche
> ore).
> • Rileva le tue partecipazioni — l'accesso facoltativo con Google legge le
> tue pagine di test e mostra lo stato in tempo reale (aperta/al completo) e
> le beta in cui sei già.
> • Amplia il catalogo condiviso — un'opzione (disattivata per impostazione
> predefinita) contribuisce in forma anonima con i programmi appena trovati;
> vengono inviati solo nomi di pacchetto.
>
> PRIVACY
> Niente pubblicità, niente analisi, niente telemetria. La tua lista di app e
> le tue partecipazioni restano sul tuo dispositivo; l'unica eccezione è la
> condivisione facoltativa del catalogo descritta sopra. Open source
> (Apache 2.0).
>
> LINGUE
> Inglese, spagnolo, tedesco, francese, italiano, portoghese brasiliano e
> finlandese.
>
> DA SAPERE
> Anche senza accesso puoi comunque sfogliare le beta trovate, aprire le loro
> pagine di iscrizione e impostare promemoria. La lettura automatica delle
> tue pagine di test è una zona grigia nei termini di Google; BetaScout è
> volutamente prudente, ma un piccolo rischio per l'account non si può
> escludere.

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
