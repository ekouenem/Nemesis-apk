# NEMESIS Android — v0.1.0-beta.14

**Préversion sideload** avec NemApi intégré comme moteur principal. Cette release contient trois APK sélectionnés par architecture. Ce n’est pas une version Google Play.

## Téléchargements

Dans la [release GitHub beta.14](https://github.com/ekouenem/Nemesis-apk/releases/tag/v0.1.0-beta.14), choisir un seul fichier :

- **Intel / x86** (inclut `x86` et `x86_64`) : `NEMESIS-v0.1.0-beta.14-x86.apk`.
- **ARM 32 bits** : `NEMESIS-v0.1.0-beta.14-armeabi-v7a.apk`.
- **ARM 64 bits** : `NEMESIS-v0.1.0-beta.14-arm64-v8a.apk`.

Android 8 / API 26 minimum. Installer l’APK correspondant **par-dessus** l’application existante. Ne pas désinstaller l’app et ne pas effacer ses données. Les trois builds conservent la signature de développement/debug utilisée précédemment ; ce n’est pas une signature de production.

- `versionCode 17`, `versionName 0.1.0-beta.14`.
- `targetSdk 28`, conservé pour le contrat de stockage historique.
- NemApi reste le moteur principal ; aucun jeton ni secret de développement ne doit être ajouté au dépôt ou à l’APK.

## Correctif de stabilité beta.14

Le délai normal de 8 secondes entre deux messages est maintenant attendu localement par NemApi au lieu d’être renvoyé comme un cooldown HTTP. Seul un challenge de sécurité réellement refusé déclenche encore un cooldown non rejouable. Les refus avant soumission remontent directement avec leur code ; ils ne sont plus enveloppés dans un faux « Flux interrompu / Échec après 1 tentatives ». Voir [`docs/BETA14-PACING-DEBUG.md`](docs/BETA14-PACING-DEBUG.md).

## Correctifs NemApi inclus

- Les refus de challenge de sécurité, le cooldown, la session expirée, l’annulation et certaines erreurs de configuration sont des erreurs explicites non rejouées automatiquement par le client Python.
- Temporisation croissante pour les autres erreurs HTTP ; cadence minimale de 8 s entre les envois et refroidissement après un challenge refusé.
- Restauration des cookies par nom, sans écraser un cookie vivant ; copie de secours limitée à 12 h et abandonnée si le provider refuse la session.
- Attente du challenge portée à 45 s ; erreur de challenge visible à l’utilisateur et état consultable par l’API NemApi.
- Mode d’identité du WebView, hook limité aux origines DeepSeek, et diagnostics de l’empreinte navigateur / du challenge PoW.

Ces changements visent à éviter les répétitions immédiates et les réinjections de session obsolètes. **Ils ne garantissent pas qu’un compte ne sera jamais bloqué** : les décisions d’accès restent celles de DeepSeek. Le rapport source est [`docs/RAPPORT-DeepSeek-blocage-comptes.md`](docs/RAPPORT-DeepSeek-blocage-comptes.md) ; le patch exact reçu est joint séparément comme asset à la release GitHub beta.14.

## Vérifications et limites

- Tests Python ciblés : **50 réussis** ; `py_compile` du client NemApi réussi.
- Tests JS `nemapi/tools/test-hook.js` : tous réussis.
- Compilation release x86 avec les changements NemApi : réussie ; le Kotlin modifié par le patch compile.
- Les APK x86/x86_64, ARM32 et ARM64 sont compilées et leur manifeste, signature et ABI sont contrôlés avant publication.
- Aucun test de connexion DeepSeek sur compte réel, aucune mesure sur 7 jours, et aucun essai du correctif sur appareil physique ARM n’ont été réalisés ici. Ne pas interpréter le build comme une garantie anti-blocage.
- L’installation Alpine sur ARM reste à confirmer sur appareil : le correctif du magasin CA est inclus, mais l’émulateur ARM du Codespace n’a pas pu être installé faute d’espace disque. Si une erreur TLS persiste, vérifier date/heure Android et réseau ; ne pas désactiver TLS.
- Debian PRoot/APT reste en panne connue : `201::URIDone (14: Bad address)` avec avertissements `chown`/`chmod`. Voir `docs/DEBUG-DEBIAN-PROOT-APT.md`. Debian et sudo ne sont pas déclarés fonctionnels.
- La signature est debug, et non une signature de production.

## Fonctions de l’application

- NEMESIS Agent est le parcours par défaut ; Code et les projets ont leurs sections distinctes.
- Les projets sont des espaces Nemesis Code avec discussions et contexte partagés.
- Les conversations, réglages et workspaces restent dans les données privées locales de l’application ; sauvegarder les éléments importants avant toute manipulation.

## Reproduire les builds

Utiliser JDK 21, puis dans le Codespace :

```bash
export ANDROID_HOME=/workspaces/android-sdk
export ANDROID_SDK_ROOT=/workspaces/android-sdk
./gradlew --no-daemon --offline -PnemesisAbis=x86,x86_64 :app:assembleRelease
./gradlew --no-daemon --offline -PnemesisAbis=armeabi-v7a :app:assembleRelease
./gradlew --no-daemon --offline -PnemesisAbis=arm64-v8a :app:assembleRelease
```

Chaque commande produit `app/build/outputs/apk/release/app-release.apk` pour les ABI demandées. Ne jamais ajouter `credentials.txt`, clés API, cookies, keystores privés ou données d’émulateur au dépôt.
