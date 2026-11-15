# NEMESIS Android v0.1.0-beta.15

Correctifs pour l'installation des environnements PRoot Debian et Alpine, plus sûreté de reprise et de cache.

## Changements

- Debian : téléchargement des `.deb` par l'hôte, comparaison du SHA-256 annoncé par l'index APT signé, y compris pour les archives déjà en cache ; rejet de noms de paquets et URI mal formés. En cas d'échec du chemin hors-ligne, le bootstrap conserve son repli en ligne.
- Reprise : le rootfs final est activé avec un renommage atomique sans remplacement. Si cible et staging existent simultanément, la tâche refuse d'effacer l'un ou l'autre.
- Alpine : HTTPS tenté en premier, puis repli HTTP si l'étape `apk update` échoue. La vérification de signature APK reste active ; HTTP supprime toutefois l'authentification du transport.
- Journal PRoot complet consultable dans l'application.

## Vérification

- 58 tests Python réussis.
- `py_compile` et `git diff --check` réussis.
- `./gradlew assembleRelease --no-daemon` réussi avec JDK 17 et Android SDK 34.
- APK universelle : `versionCode 18`, `versionName 0.1.0-beta.15`; contient `libproot.so` pour `armeabi-v7a`, `arm64-v8a`, `x86` et `x86_64` ; signature APK vérifiée.
- Aucun test d'installation Debian/Alpine sur appareil ARMv7 n'a été réalisé. Cette release ne prétend pas le valider.
- Signée avec la clé debug Android du projet, pas une clé de production.
