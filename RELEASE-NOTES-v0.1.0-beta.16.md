# NEMESIS Android v0.1.0-beta.16

Correctifs pour l'onglet Projets, l'import de fichiers dans le workspace et le rapatriement de fichiers PRoot, plus une barre latérale réorganisée.

## Changements

- Projets : le premier message d'une discussion de projet ne provoque plus l'erreur « Renommage impossible : Journal de migration invalide ». Une discussion rattachée à un projet ne renomme que son titre (le dossier du projet est partagé par toutes ses discussions) ; elle est bien créée et le message est transmis.
- Workspace : un fichier importé s'affiche immédiatement dans l'interface. Le dossier est réservé, créé et marqué initialisé avant l'écriture (`workspace_prepare`), au lieu de réallouer un dossier « (2) » vide qui masquait le fichier.
- PRoot : `download_file` fonctionne en Alpine/Debian PRoot et plus seulement en SSH. `/workspace` correspond directement au dossier de la discussion sur le stockage Android ; un livrable créé ailleurs dans le rootfs est copié dans le dossier local.
- Barre latérale : navigation verticale claire et non empilée en trois sections (Navigation, Discussions, Outils), en tiroir et en sidebar large ; les discussions Code sont désormais listées avec une étiquette de mode.

## Vérification

- 93 tests Python réussis, dont des tests de régression pour le renommage de projet, la stabilité du dossier de workspace et l'export PRoot.
- `py_compile` et `git diff --check` réussis.
- `./gradlew assembleRelease --no-daemon` réussi pour `armeabi-v7a`, `arm64-v8a`, `x86` et `x86_64`.
- Correctifs vérifiés sur émulateur x86 Android 8.1 : création d'une discussion de projet et envoi d'un message sans l'erreur de migration, nouvelle barre latérale.
- Signature debug Android du projet, pas une signature de production.