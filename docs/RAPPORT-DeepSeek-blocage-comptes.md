# Rapport d'analyse — blocage des comptes DeepSeek et déconnexions de session dans NEMESIS (APK)

**Périmètre :** dépôt `ekouenem/Nemesis-apk` (branche `fix/deepseek-account-blocking`, patch joint) comparé au dépôt amont `teteekoue/NemApi`.
**Date :** 2026-11-15 — **État :** analyse de code + correctifs rédigés (le code Kotlin **n'a pas pu être compilé** ici : aucun JDK/Android SDK dans l'environnement ; le JS est validé par la suite de tests du dépôt, le Python par `py_compile`).

---

## 1. Verdict en une page

Le blocage des comptes DeepSeek dans l'APK **n'est pas un bug du DOM** : c'est le **moteur de risque du provider** qui classe l'environnement WebView Android comme automatisé, **aggravé par une boucle de relance automatique** qui transforme un refus ponctuel en série de refus sur le même compte.

Deux symptômes distincts, deux causes racines distinctes :

| Symptôme | Cause racine | Correctif fourni |
|---|---|---|
| **Comptes DeepSeek bloqués très vite** | (a) empreinte WebView incohérente et instrumentée + (b) **relance automatique 3× à 1 s** après un refus de vérification de sécurité (PoW) | P0-1, P0-2, P0-3, P1-1, P1-2 |
| **Comptes déconnectés régulièrement dans la WebView** | restauration de session par comparaison de **longueur** de chaîne de cookies → réinjection d'une session **révoquée** par-dessus la session valide | P0-4 |

**La différence avec NemApi original (Linux) est structurante, pas marginale :** côté Linux, la page DeepSeek tourne dans un **vrai Chromium** et **le proxy Python ne réessaie jamais**. Côté APK, la page tourne dans un **WebView Android** (moteur Chromium contraint, sans profil, sans extension, sans `window.chrome`, en-têtes différents) et le **client Python de l'APK réessaie 3 fois à 1 seconde d'intervalle**. Ces deux différences suffisent à expliquer que « ça marche sur Linux et pas sur Android ».

**Le correctif le plus rentable n'est pas un correctif de WebView :** c'est **supprimer la relance automatique** et **rendre le refus de vérification de sécurité visible et non rejouable**. C'est ce que fait le patch joint.

---

## 2. Chaîne causale détaillée

### 2.1 Le refus de vérification de sécurité (PoW) — ce que DeepSeek exige

Chaque appel à `POST /api/v0/chat/completion` sur le site DeepSeek exige un en-tête `x-ds-pow-response` : une **preuve de travail** calculée par le front-end lui-même (`DeepSeekHashV1`, variante de SHA3-256 exécutée dans un module WebAssembly `sha3_wasm_bg.wasm`, Difficulté ≈ 144 000 tours). Le challenge est obtenu via `POST /api/v0/chat/create_pow_challenge` et la réponse porte une durée de validité (`expire_at`).

> Sources : [github.com/shaohuahuawww/deepseek-pow](https://github.com/shaohuahuawww/deepseek-pow) · [github.com/sums001/Deepseek-API/blob/deepseek/pow.py](https://github.com/sums001/Deepseek-API/blob/14d6227d2f0104038677872984eaddee59937e0e/deepseek/pow.py) · [github.com/kittors/deepseek-web-api/blob/main/docs/architecture.md](https://github.com/kittors/deepseek-web-api/blob/main/docs/architecture.md)

Deux conséquences directes pour l'APK :

1. **Le PoW est calculé par la page**, donc par le moteur du WebView, avec les ressources de l'appareil. Sur un appareil mobile, et **a fortiori si le WebView est en arrière-plan**, ce calcul est plus lent (minuteries et Web Workers bridés par Chromium quand la page n'est plus visible) et **peut dépasser la durée de validité du challenge**. Résultat : `CHALLENGE_REQUIRED` (code métier `40303`) alors que la page est parfaitement saine.
2. **Un challenge raté est un signal de risque**, pas une panne réseau. C'est ce qui explique le passage brutal de « ça fonctionne » à « le compte est bloqué ».

### 2.2 La boucle de relance automatique — le vrai multiplicateur

Dans l'APK, le client Python du provider rejoue toute erreur HTTP :

```
app/src/main/python/providers/nemapi_v3.py:97   self.max_retries = 3
app/src/main/python/providers/nemapi_v3.py:98   self.retry_delay = 1
app/src/main/python/providers/nemapi_v3.py:272  if attempt < self.max_retries: time.sleep(self.retry_delay)
```

Et côté moteur, le refus de vérification de sécurité **n'avait pas de code propre** : il remontait en `upstream_error` → HTTP 400 (`api/ApiServer.kt`, `httpFor()`), donc **non 200**, donc **relancé**.

Résultat concret, sur un challenge :
`challenge refusé` → HTTP 400 → `sleep(1)` → **nouvel envoi DeepSeek** → challenge refusé → `sleep(1)` → **nouvel envoi** → …

Trois requêtes de complétion identiques en 2 secondes sur le même compte, **juste après un refus de sécurité**. C'est exactement la signature que le moteur de risque sanctionne. Le proxy Linux amont, lui, **ne réessaie jamais** (`grep retry` sur `NemApi/proxy/nemapi` → aucun mécanisme de relance) : c'est l'écart le plus net entre les deux plateformes.

### 2.3 L'empreinte du WebView n'est pas celle d'un Chrome

`web/WebViewHost.kt:28-30` (avant correctif) :

```kotlin
const val DESKTOP_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/126.0.0.0 Safari/537.36"
```

Le UA annonce un **Chrome 126 de bureau**, mais le moteur est le **WebView Android installé sur l'appareil** (souvent un Chromium bien plus récent, ou 120/130/140). Les incohérences sont lisibles en JavaScript par la page DeepSeek :

| Ce que la page observe | WebView Android (spoofé « Chrome 126 Linux ») | Chrome 126 de bureau ( NemApi original) |
|---|---|---|
| `navigator.userAgent` | « X11; Linux x86_64 » | cohérent |
| `navigator.userAgentData.brands` / `.platform` | version et plateforme **réelles** (Android) | cohérent |
| `navigator.plugins.length` | **0** (pas de lecteur PDF) | 5 |
| `window.chrome` | **undefined** | présent |
| `navigator.maxTouchPoints` | 5 | 0 |
| `screen.width/height` | 412×915 (téléphone) | 1920×1080 |
| WebGL `UNMASKED_RENDERER` | « Android WebView » / SwiftShader (émulateur) | GPU réel |
| `devicePixelRatio`, `hardwareConcurrency`, `deviceMemory` | valeurs téléphone/mobile | valeurs bureau |
| `Sec-CH-UA*` (si envoyés) | version **réelle** ≠ 126 | 126 |

Une seule de ces incohérences est déjà un signal ; leur ensemble est un profil « robot ».

S'y ajoute un signal **réseau** : le WebView Android ajoute automatiquement l'en-tête **`X-Requested-With: <nom du package APK>`** à ses requêtes. La suppression de cet en-tête a été **annulée** par Google (la liste blanche est dépréciée et sans effet) : il est donc **toujours envoyé** par les WebView Android. Un vrai Chrome n'envoie jamais cet en-tête. C'est un marqueur d'application Android direct, présent sur chaque requête, y compris la requête de complétion.

> Source : [developer.chrome.com/blog/android-webview-header-privacy](https://developer.chrome.com/blog/android-webview-header-privacy/) · [WebSettingsCompat.setRequestedWithHeaderOriginAllowList (déprécié)](https://android.googlesource.com/platform/frameworks/support/+/ded992faa29a440cedb4939d474bfb4dbf551d25/webkit/webkit/src/main/java/androidx/webkit/WebSettingsCompat.java)

### 2.4 Le hook s'auto-signale

`nemapi/app/src/main/assets/js/hook.js` (avant correctif) remplace `window.fetch` (ligne 386) et `XMLHttpRequest.prototype.open/send` (ligne 437) par des fonctions JavaScript ordinaires.

Le test d'automatisation le plus courant est :

```js
Function.prototype.toString.call(window.fetch).includes("[native code]")
```

Avant correctif → **false** (notre source JS est renvoyée) → la page sait que le réseau est instrumenté. Même test possible sur `XMLHttpRequest.prototype.open`.

Aggravant : le hook était installé sur **toutes les origines** (`WebViewHost.kt:159`, `setOf("*")`), y compris le CDN qui sert le module WASM du PoW et les redirections de connexion. Un script d'anti-bot chargé sur une autre origine voit lui aussi l'instrumentation.

### 2.5 La WebView n'est presque jamais « visible »

- `BrowserActivity.onDestroy()` détache le WebView de la fenêtre (`unbindContainer`) ;
- côté NEMESIS, `MainActivity.onDestroy()` fait de même (`app/src/main/java/com/nemesis/apk/MainActivity.kt:251`) ;
- quand l'application passe en arrière-plan, la fenêtre de l'Activity n'est plus visible → Chromium considère la page comme **cachée** → minuteries ralenties, Workers gelables.

C'est précisément le scénario où **le PoW expire** (2.1). Le code gère déjà le cas « la page résout le challenge elle-même » (`ChallengeGrace`, 12 s), mais 12 s ne suffit pas sur un appareil lent ou bridé, et à l'expiration le moteur renvoyait une erreur générique **relancée 3 fois** par le client Python (2.2).

### 2.6 La restauration de session déconnecte les comptes

`core/Cookies.kt` (avant correctif) :

```kotlin
if (snapshot().length >= saved.length) return false   // ligne 51 : comparaison par LONGUEUR
saved.split(";").map { it.trim() }.filter { it.contains("=") }
    .forEach { cm.setCookie(DOMAIN, it) }               // ligne 56 : écrasement possible
```

Deux défauts :

1. **Comparaison par longueur.** Après une rotation de cookie côté serveur (le jeu valide est souvent plus court que l'ancien), la condition `snapshot().length >= saved.length` est fausse → la copie de secours est **réinjectée par-dessus** la session valide. Le provider reçoit un ancien identifiant de session, le juge révoqué, et **déconnecte le compte**.
2. **Réinjection sans attributs.** `setCookie(url, "nom=valeur")` sans `Path`/`SameSite`/`Secure` crée un cookie « host-only » Lax — différent du cookie d'origine. Le cookie réinjecté n'est pas forcément accepté sur `/api/v0/*`.
3. **Aucune limite d'âge** : une copie de plusieurs jours est réinjectée au démarrage.

C'est un mécanisme de déconnexion périodique, indépendant de tout risque DeepSeek.

---

## 3. Les correctifs fournis (branche `fix/deepseek-account-blocking`)

### P0 — indispensable (suppression du mécanisme de blocage)

| # | Fichier | Modification | Effet |
|---|---|---|---|
| **P0-1** | `core/Errors.kt`, `core/DeepSeek.kt`, `api/ApiServer.kt` | Nouveau code d'erreur `security_challenge` (HTTP **429**, type OpenAI `rate_limit_error`) ; `provider_cooldown` (429) ; `login_required` → **401** | Le refus de vérification de sécurité devient **un état explicite**, pas une panne générique. Un client sait qu'il ne faut pas rejouer. |
| **P0-2** | `app/src/main/python/providers/nemapi_v3.py` | Liste `NON_RETRYABLE` = {`security_challenge`, `provider_cooldown`, `login_required`, `user_intervention_required`, `provider_not_configured`, `cancelled`} : **aucune relance automatique**. Backoff croissant (1 s → 2 s → … plafonné à 30 s) pour les autres erreurs. | **Supprime la boucle 3× à 1 s.** C'est le correctif qui protège réellement les comptes. |
| **P0-3** | `core/Pacing.kt` (nouveau) + `core/DeepSeek.kt` | Intervalle minimal de **8 s** entre deux envois ; après un challenge, refroidissement de **3 min** (15 min après 3 refus) ; l'API renvoie `provider_cooldown` pendant ce temps. | Le compteur « requêtes rapprochées » ne monte plus. Le compte « respire ». |
| **P0-4** | `core/Cookies.kt` + `core/DeepSeek.kt` | Restauration **par nom de cookie** (plus jamais par longueur) : un cookie vivant n'est **jamais écrasé** ; copie limitée à **12 h** ; `flush()` systématique ; `forgetIfLoginRequired()` appelé quand la page signale une session expirée (y compris le code métier `40003` → `authentication_error`). | Supprime la **déconnexion périodique** due à la réinjection d'une session révoquée. |
| **P0-5** | `core/DeepSeek.kt` | Arrêt **immédiat** sur `login_required` (au lieu d'attendre 20 s) : plus aucune requête DeepSeek envoyée sans session authentifiée. | Les échecs d'authentification ne nourrissent plus le score de risque. |
| **P0-6** | `core/ChallengeGrace.kt` | Attente de réaction native de la page après un challenge : 12 s → **45 s**. | Laisse au PoW le temps d'être résolu par la page (notamment en arrière-plan). |

### P1 — réduction de l'empreinte (le WebView reste un WebView)

| # | Fichier | Modification | Effet |
|---|---|---|---|
| **P1-1** | `web/WebViewHost.kt` | Le UA annonce **la version de Chrome réellement installée** (`WebView.getCurrentWebViewPackage().versionName`) au lieu de `Chrome/126` figé. Deux modes d'identité : `desktop` (par défaut, DOM connu) et `android` (UA cohérent avec l'appareil). Bascule via `POST /api/identity {"mode":"android"}`. | Supprime l'incohérence de version UA ↔ moteur. Le mode `android` rend l'ensemble UA/écran/tactile/WebGL homogène. |
| **P1-2** | `assets/js/hook.js` | Les fonctions enveloppées sont marquées « native code » (`Function.prototype.toString` patché via `WeakMap`, sur **nos** seules fonctions). Le hook **ne s'installe plus** que sur l'origine du provider (`chat.deepseek.com`), plus `setOf("*")`. | Élimine le test d'instrumentation le plus courant et réduit la surface exposée. |
| **P1-3** | `web/WebViewHost.kt` | Les en-têtes envoyés au provider sont **journalisés une fois** (lecture seule, aucune interception). | Permet de **mesurer** la présence de `X-Requested-With` et de `Sec-CH-UA` sur l'appareil réel. |
| **P1-4** | `assets/js/hook.js` | Diagnostic PoW : présence de l'en-tête `x-ds-pow-response` sur l'appel de complétion, et **délai challenge → complétion** ; enveloppage de `XMLHttpRequest.setRequestHeader` pour lire les noms d'en-têtes. | Prouve objectivement si le PoW est résolu et si le délai est anormal (page bridée). |
| **P1-5** | `core/State.kt`, `app/.../NemesisService.kt` | `AppState.onSecurityChallenge` + **notification** NEMESIS : « DeepSeek : vérification de sécurité — ouvre NEMESIS pour vérifier la session ». Champs `security_challenges`, `last_security_challenge_at`, `cooldown_remaining_ms` dans `/api/status`. | Le refus devient **visible** pour l'utilisateur, qui vérifie la session — au lieu d'un échec silencieux dans le vide. |

### Diagnostics livrés (lecture seule, aucun effet sur le provider)

- `GET /api/debug/fingerprint` → empreinte lue **dans la page** (UA, `userAgentData`, plugins, `window.chrome`, tactile, écran, viewport, DPR, langues, fuseau, WebGL, `visibilityState`, test « natif » de `fetch`/`XHR`).
- `GET /api/identity` (GET/POST) → mode d'identité, version Chrome réelle, UA appliqué, fournisseur WebView installé.
- `nemapi/app/src/main/assets/web/fingerprint.html` → **la même page à ouvrir dans un vrai Chrome** sur le même appareil, pour comparer ligne à ligne.

---

## 4. Ce qui n'est PAS corrigé (et pourquoi) — suite P2, à valider sur un appareil réel

1. **`X-Requested-With: com.nemap.android`** — non supprimé. Android ne permet pas de le retirer sans **intercepter et rejouer la requête** soi-même (`shouldInterceptRequest` + `WebResourceResponse`), ce qui casse le flux SSE du PoW/complétion. La solution propre consiste à faire transiter les appels provider par un petit proxy local (OkHttp) qui recopie les en-têtes (cookies, `x-ds-pow-response`, `authorization`) en **supprimant** `X-Requested-With` et en **normalisant** `Sec-CH-UA*` sur la version réelle du moteur. **C'est la piste restante la plus rentable si les comptes sont toujours bloqués après P0+P1**, mais elle demande un test réel (rejouer le SSE, gérer les redirections, ne jamais journaliser les jetons).
2. **Le WebView n'est pas un Chrome.** Aucun correctif ne rend un WebView indistinguable d'un navigateur de bureau (`window.chrome`, plugins, profil, extensions, GPU). Deux leviers restent :
   - le mode `android` (P1-1) : identité **cohérente** plutôt que **trompeuse** — c'est en général ce que les moteurs de risque récompensent ;
   - ou faire tourner le vrai NemApi Linux (PRoot + Chromium) sur l'appareil, ce qui reproduit exactement l'environnement où ça ne bloque pas.
3. **Gardez le WebView visible** pendant les générations (option « overlay » du service de premier plan, permission `SYSTEM_ALERT_WINDOW`) : Chromium ne bride alors ni minuteries ni Workers. Non implémenté ici : permission sensible et risque de fuite d'affichage ; à traiter au cas par cas.
4. **Un seul compte par profil / par IP.** Si plusieurs comptes DeepSeek sont utilisés depuis le même appareil et la même adresse IP, le provider les corrèle. C'est hors du champ du code.
5. **Émulateur = risque maximal.** Sur un émulateur, le renderer WebGL est `SwiftShader`, le modèle d'appareil est générique et l'IP est celle d'un hébergeur de Codespaces : le profil est un signal d'automatisation évident. **Testez d'abord sur un appareil physique** avant de conclure sur le correctif.

---

## 5. Plan de vérification (à faire avant de conclure)

**J0 — état des lieux (sans toucher au code)**
1. `adb logcat -s NemApi` pendant une génération : relever `WebView provider : …` (WebView Google ou AOSP ? version ?), `en-têtes provider : …` (X-Requested-With présent ? Sec-CH-UA ?), `refus de sécurité du provider` (déjà présent avant le patch).
2. `curl http://127.0.0.1:8090/api/debug/fingerprint` → note la empreinte.
3. Ouvrir `assets/web/fingerprint.html` **dans le Chrome du même appareil** → compare. Liste les écarts.
4. Compter les refus : `curl http://127.0.0.1:8090/api/status` → `errors_by_code`, `security_challenges` (après patch).

**J1 — validation du correctif**
5. Appliquer le patch, compiler (`./gradlew :app:assembleDebug`), installer **sans désinstaller** (les cookies sont precious).
6. Provoquer un refus de PoW (réponse lente réseau, ou appareil lent) et vérifier : **un seul** envoi dans les logs, HTTP 429 `security_challenge`, notification NEMESIS, **aucune** relance du client Python.
7. Vérifier la session : laisser l'application overnight, redémarrer, confirmer que la session DeepSeek est **toujours** connectée (le bug de re-injection est corrigé).

**J2 — mesure sur 7 jours**
8. Compter `security_challenges` et `cooldown_remaining_ms` par jour ; le compte ne doit plus être bloqué.
9. Si un blocage persiste : basculer en mode `android` (`POST /api/identity`) et recommencer le cycle — c'est le test qui tranche entre « UA incohérent » et « autre cause ».

**Critère de succès :** sur 7 jours d'usage agent normal, **0 blocage de compte**, **0 déconnexion de session**, **1 seul envoi par tour** (vérifiable dans les logs).

---

## 6. Fichiers livrés

- `0001-fix-deepseek-no-more-account-blocking.patch` — patch unifié complet (13 fichiers, +740 /-33 lignes), applicable avec `git am` ou `git apply` sur le dépôt `Nemesis-apk` (base `3e867a5`).
- Branche `fix/deepseek-account-blocking` du clone local, avec le commit correspondant.
- Fichiers modifiés : `core/Errors.kt`, `core/Pacing.kt` (nouveau), `core/DeepSeek.kt`, `core/Cookies.kt`, `core/ChallengeGrace.kt`, `core/State.kt`, `web/WebViewHost.kt`, `api/ApiServer.kt`, `assets/js/hook.js`, `assets/web/fingerprint.html` (nouveau), `app/.../NemesisService.kt`, `app/src/main/python/providers/nemapi_v3.py`, `CHANGELOG.md`.

**Limite de vérification :** le code Kotlin n'a pas pu être compilé dans cet environnement (pas de JDK ni d'Android SDK) ; il a été relu ligne à ligne. Le JavaScript est validé par `nemapi/tools/test-hook.js` (**tous les tests passent**), le Python par `py_compile`. **Compilez et testez sur appareil avant toute diffusion.**

---

## Mise à jour après intégration dans NEMESIS Android beta.13

Le patch joint a été appliqué au dépôt NEMESIS Android. Contrairement à l'état indiqué dans le rapport initial, le code Kotlin NemApi a ensuite été compilé dans les trois configurations de release. Les tests JS du hook et les 49 tests Python ciblés passent. Le module Kotlin ne contient pas de tests unitaires (`testReleaseUnitTest: NO-SOURCE`).

Les APK x86/x86_64, armeabi-v7a et arm64-v8a ont été compilées, signées et vérifiées statiquement. Aucun test sur compte DeepSeek réel, aucune mesure sur 7 jours et aucun essai ARM physique ne sont revendiqués ; le succès de compilation ne démontre pas l'absence de blocage par le provider.
