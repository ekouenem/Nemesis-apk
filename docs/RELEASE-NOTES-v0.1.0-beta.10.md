# NEMESIS Android v0.1.0-beta.10

Préversion sideload poursuivant la série beta. Le moteur principal reste NemApi intégré ; l’application n’est pas une diffusion Google Play.

## Nouveautés

- NEMESIS Agent est désormais le mode par défaut ; le sélecteur glissant Agent/Code a été retiré.
- Les discussions Agent classiques restent dans le tiroir. Les discussions Code classiques sont dans la section Code.
- Les conversations de projet ne se mélangent pas aux fils classiques : chaque projet est maintenant un espace Nemesis Code avec ses propres discussions, fichiers et contexte.
- Création, ouverture, renommage et suppression de projets ; création de conversations liées au projet, affichées et gérées dans la page de ce projet.
- Migration des anciens projets et de leurs conversations associées vers le mode Code sans supprimer leur contenu.
- Diagnostic rédigé pour le défaut restant de l’installation Debian : `docs/DEBUG-DEBIAN-PROOT-APT.md`.

## Vérifications

- `PYTHONPATH=app/src/main/python python3 -m unittest discover -s tests -p test_mobile_agent.py` : **48 tests réussis**.
- `./gradlew --no-daemon --offline :app:assembleRelease` : réussi.
- Installation de l’APK release par-dessus l’application existante dans l’émulateur x86 sans désinstaller ni effacer les données.
- Vérification UI dans l’émulateur : Agent par défaut, Code, tiroir réservé aux discussions Agent, création d’un projet Code et d’une discussion liée, suppression du projet de test.
- Alpine : terminal PRoot et commande `id` fonctionnels ; `id nemesis` renvoie UID 1000. Les fichiers du binaire sudo et de la règle sudoers existent. L’authentification sudo avec le mot de passe n’a pas été testée de bout en bout.

## Problème Debian reporté

L’installation Debian échoue toujours après le téléchargement/extraction au moment d’installer les paquets : `201::URIDone (14: Bad address)`, avec avertissements `chown`/`chmod`. La résolution DNS est franchie, mais la cause du nouvel échec n’est pas encore établie. Le cache APT sous `/tmp` a été essayé et n’a pas corrigé le défaut ; ce changement a été retiré. Debian, l’utilisateur `nemesis` et sudo ne sont pas annoncés comme fonctionnels. Voir `docs/DEBUG-DEBIAN-PROOT-APT.md`.

## Installation, signature et compatibilité

- Android 8.0 / API 26 minimum ; ABIs `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`.
- `versionCode 13`, `versionName 0.1.0-beta.10`.
- APK universelle signée avec le certificat de développement/debug conservé pour les mises à jour sideload ; **pas une signature de production**.
- `targetSdk 28` conservé ; pas de prise en charge Google Play dans cette préversion.
- Faire la mise à jour par-dessus la version existante. Ne pas désinstaller l’application ou effacer ses données.

## Confidentialité et données

Les conversations, paramètres et workspaces restent dans le stockage privé de l’application, sous réserve des dossiers explicitement partagés. Aucun secret de développement, jeton, cookie ou keystore privé n’est inclus dans l’APK ou les notes de release.
