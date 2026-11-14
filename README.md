# NEMESIS Android — v0.1.0-beta.10

**Préversion sideload** de NEMESIS Android. NemApi intégré reste le moteur principal de l’application ; DeepSeek est utilisé via NemApi. Cette publication reprend les changements d’interface Agent/Code/Projets et les diagnostics de l’environnement Linux local. Elle n’est pas une version Google Play.

## Télécharger et installer

Télécharger [`NEMESIS-v0.1.0-beta.10.apk`](https://github.com/ekouenem/Nemesis-apk/releases/download/v0.1.0-beta.10/NEMESIS-v0.1.0-beta.10.apk) dans la [page de la release](https://github.com/ekouenem/Nemesis-apk/releases/tag/v0.1.0-beta.10). Android 8.0 / API 26 minimum ; APK universelle pour `arm64-v8a`, `armeabi-v7a`, `x86_64` et `x86`.

- `versionCode 13`, `versionName 0.1.0-beta.10`.
- Certificat de développement/debug conservé pour les mises à jour de sideload ; **ce n’est pas une signature de production**.
- `targetSdk 28` reste en place pour le contrat de stockage historique. Une diffusion Play nécessite un chantier distinct de migration du stockage et de cible SDK.
- Effectuer la mise à jour par-dessus l’application existante et conserver ses données. Ne pas désinstaller l’application pour installer cette préversion.

## Changements

- **NEMESIS Agent** est le parcours par défaut ; le sélecteur Agent/Code glissant a été retiré.
- **Code** a sa propre section et sa liste de discussions classiques Code.
- Le tiroir des discussions classiques n’affiche que les discussions Agent. Les discussions Code liées à un projet restent dans ce projet.
- Les projets sont désormais des espaces Nemesis Code : création sans choix de mode, conversations liées au projet, fichiers et contexte partagés, options de renommage/suppression et accès aux conversations du projet depuis sa page.
- Les anciens projets et leurs discussions liées sont migrés vers Code au démarrage ; ils ne sont pas effacés.
- Le prompt système Agent a été renommé et le contexte de projet a été conservé.
- Ajout de `docs/DEBUG-DEBIAN-PROOT-APT.md`, qui consigne les preuves et les prochaines étapes du diagnostic Debian.

## Vérifications effectuées

- Tests Python ciblés de `mobile_agent` : **48 réussis**.
- `:app:assembleRelease` compilé avec succès.
- APK installée par-dessus l’installation existante dans l’émulateur x86 ; aucune désinstallation ni remise à zéro des données.
- Parcours UI vérifiés dans l’émulateur : Agent par défaut, Code, tiroir filtré, création d’un projet Code, création d’une discussion liée et suppression explicite du projet de test.
- Alpine : terminal PRoot ouvert ; `id` a fonctionné et `id nemesis` a renvoyé UID 1000. Le binaire sudo et la règle sudoers existent. **L’authentification sudo avec le mot de passe n’a pas été validée indépendamment.** Le rootfs reste PRoot et n’accorde pas root sur Android.

## Limitation connue — Debian

L’installation Debian n’est pas déclarée fonctionnelle dans cette préversion. Après la correction de résolution DNS Android, l’installation des paquets échoue encore avec `201::URIDone (14: Bad address)` et des avertissements `chown`/`chmod`. Le déplacement du cache APT vers `/tmp` n’a pas résolu le problème et a été retiré du runtime. Voir [le diagnostic Debian/PRoot/APT](docs/DEBUG-DEBIAN-PROOT-APT.md). Ne pas considérer l’utilisateur Debian `nemesis` ni sudo comme configurés tant qu’une nouvelle installation complète n’a pas réussi.

## Autres limites

- Préversion expérimentale ; certificats de développement, intégrations externes et services distants dépendent de leur propre configuration.
- Pas de validation sur appareil physique ARM, pas de test d’authentification réelle OAuth/DeepSeek revendiqué pour cette publication.
- Les conversations, espaces de travail et projets restent stockés localement dans les données privées de l’application ; sauvegarder les éléments importants avant toute manipulation.

## Développement

Dans le Codespace du projet, avec JDK et SDK Android disponibles :

```bash
export ANDROID_HOME=/workspaces/android-sdk
export ANDROID_SDK_ROOT=/workspaces/android-sdk
./gradlew --no-daemon --offline :app:assembleRelease
```

Sortie : `app/build/outputs/apk/release/app-release.apk`. `credentials.txt`, clés API, cookies, keystores privés et données d’émulateur ne doivent jamais être ajoutés au dépôt.
