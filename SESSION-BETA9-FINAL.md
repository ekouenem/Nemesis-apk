# Bilan final — session NEMESIS Android beta.9

**Date :** 13 novembre 2026  
**Dépôt :** [`ekouenem/Nemesis-apk`](https://github.com/ekouenem/Nemesis-apk), branche `main`  
**Codespace :** `nemesis-android-2-coeurs-wvxrrqp9x7g5fgx75`, `/workspaces/Nemesis-apk`  
**État :** session finalisée; code poussé et préversion publiée.

## Release

- **Version :** `v0.1.0-beta.9`, `versionCode 10`, `versionName 0.1.0-beta.9`.
- **Release GitHub :** <https://github.com/ekouenem/Nemesis-apk/releases/tag/v0.1.0-beta.9>
- **Asset :** `NEMESIS-v0.1.0-beta.9.apk` (40 627 973 octets).
- **SHA-256 confirmé sur l’asset GitHub :** `f04b43f3539cdd70c6f632b807a4b26922f05b4fdd40e4052436fa60ddec4a94`.
- Le tag de release pointe sur le commit de code `4e626cf070a79fc13e34952df5c831ba9994582a`. Le présent compte rendu et la mise à jour du contexte sont poussés ensuite sur `main`.
- APK signée avec le certificat de développement déjà utilisé par beta.8 afin de permettre les mises à jour par-dessus. **Ce n’est pas une clé de signature de production.** C’est une préversion pour installation directe, pas une publication Google Play.

## Changements livrés

- **Moteur DOM :** délai mesuré à partir de l’envoi réel vers la WebView; temporisations/reprises bornées pour les observations idempotentes; état de santé après des délais répétés; récupération manuelle. Les envois et clics non idempotents ne sont pas rejoués automatiquement; une réponse incertaine doit être vérifiée avant reprise.
- **Google Workspace :** parcours OAuth natif Android, hors WebView DeepSeek; outils limités aux capacités prévues et aux discussions où le connecteur est activé.
- **GitHub :** pont de jeton avec nettoyage de la saisie, stockage protégé par Android Keystore et vérification de compte prévue avant de présenter l’accès comme vérifié. Les outils sont limités au mode et aux discussions activés.
- **Shell :** PRoot/Debian retiré du code et de l’interface après des erreurs d’exécution (`Permission denied`). Bash/SFTP utilisent un serveur SSH explicitement configuré; les fichiers Android locaux ne sont pas automatiquement montés sur le serveur; la sortie Bash est plafonnée à 512 Kio.
- Correctifs de configuration Chaquopy/ABI et de build; ajustements d’interface et du rendu Markdown du texte en streaming.

## Vérifications réellement exécutées

- **Python :** 74 tests réussis, incluant les tests synthétiques des connecteurs.
- **JavaScript :** cinq suites réussies.
- **JVM NemApi :** 13 tests réussis, dont les tests DOM de santé.
- **Instrumentation sur l’AVD existant API 27 x86 :** DOM 2/2, parsing SSH 4/4, Keystore/pont connecteurs 2/2.
- Builds `assembleDebug`, `assembleDebugAndroidTest` et `assembleRelease` réussis. L’APK candidate a été alignée/signée et vérifiée; son empreinte correspond à l’asset publié.
- Installation de la beta.9 dans l’émulateur avec `adb install -r` (sans désinstallation ni wipe), écran d’accueil affiché et aucun crash fatal observé. AVD et données utilisateur préservés.
- `git diff --check` propre; branche synchronisée avec `origin/main` au terme de la publication et de la documentation.

## Limites connues — ne pas annoncer comme validées

- **Google Workspace et GitHub :** aucun compte Google/OAuth réel, PAT d’utilisateur ou appel API authentifié n’a été utilisé. Les tests de pont/Keystore sont synthétiques. Les connecteurs sont donc expérimentaux; une configuration Cloud/OAuth Google et une validation directe restent nécessaires.
- Les API Google Sign-In/Auth utilisées dans ce prototype présentent des avertissements de dépréciation; migration recommandée avant diffusion durable.
- **DeepSeek :** aucune conversation réelle après les changements DOM; les tests DOM sont synthétiques. Aucun appareil ARM physique n’a été testé.
- **SSH :** tests de parsing seulement; aucun serveur SSH utilisateur n’a été connecté en essai réel. La première connexion conserve le modèle TOFU.
- PRoot/Debian est délibérément absent de cette release. `targetSdk 28` reste une dette de compatibilité; la bêta est un sideload GitHub et ne constitue pas une version Google Play.

## Sécurité et clôture

Aucun compte Google/GitHub personnel n’a été connecté. Le secret temporaire de publication n’a pas été ajouté au dépôt ni aux documents; aucun keystore n’a été commité. La release et son digest ont été vérifiés sur GitHub. Le fichier `CONTEXTE.md` accompagne ce bilan pour la reprise ultérieure.
