# Notes de version — NEMESIS Android

## v0.1.0-beta.16 — correctifs Projets, workspace et PRoot

- Projets : envoyer le premier message d'une discussion de projet ne renvoie plus « Renommage impossible : Journal de migration invalide ». Une discussion rattachée à un projet ne renomme plus que son titre, puisque toutes ses discussions partagent le dossier du projet. La discussion est bien créée et titrée, le message est transmis.
- Workspace : un fichier importé apparaît désormais immédiatement dans l'interface. Le dossier du workspace est réservé, créé et marqué initialisé avant l'écriture (nouvelle API `workspace_prepare`), ce qui évite l'allocation d'un dossier « (2) » vide qui masquait le fichier importé.
- PRoot : `download_file` fonctionne aussi pour l'environnement PRoot (Alpine/Debian) et plus seulement en SSH. Le rootfs PRoot vit sur le stockage Android : `/workspace` correspond directement au dossier de la discussion, et un fichier créé ailleurs (par ex. `/root`, `/tmp`) est copié dans le dossier local. Le prompt de l'agent explique désormais cette correspondance.
- Barre latérale réorganisée : navigation verticale claire et non empilée, en trois sections (Navigation : Agent, Code, Projets ; Discussions ; Outils : Automatisations, Connecteurs, Fichiers, Paramètres), identique en tiroir mobile et en sidebar large. La liste des discussions inclut désormais les discussions Code avec une étiquette de mode.
- Validation : 93 tests Python réussis (dont des tests de régression pour les trois correctifs), `py_compile` et `git diff --check` réussis, compilation release réussie pour ARMv7, ARM64, x86 et x86_64. Les correctifs ont été vérifiés sur émulateur x86 Android 8.1.
- APK signée avec la clé debug du projet, pas une clé de production.

## v0.1.0-beta.15 — correctifs d'installation PRoot

- Debian : téléchargement hôte des paquets APT avec validation du SHA-256 attendu, y compris pour les archives déjà en cache ; rejet des URI et noms de fichiers invalides.
- Reprise PRoot : activation atomique du rootfs sans remplacement ; si une cible et un staging coexistent, les deux sont conservés et l'installation s'arrête avec un diagnostic explicite.
- Alpine : HTTPS reste prioritaire ; le repli HTTP conserve la vérification des signatures APK, mais ne protège plus l'authentification du transport.
- Journal PRoot complet et bouton de consultation dans l'application ; tests de régression pour la conservation des données, le cache APT, le repli Alpine et les primitives.
- Validation effectuée : 58 tests Python et compilation Android release réussis pour ARMv7, ARM64, x86 et x86_64. Aucun test sur téléphone ARMv7 n'est revendiqué.
- APK signés avec la clé debug du projet, pas une clé de production.

## Correctif — installation de l'environnement Linux (branche `fix/proot-install-armv7-debian`)

- **Debian** : les paquets sont désormais téléchargés par l'HÔTE (Python de l'application + bundle `certifi`, la même chaîne qui télécharge le rootfs et qui fonctionne sur toutes les ABIs), déposés dans `/var/cache/apt/archives/` puis installés **hors-ligne** (`apt-get install --no-download`). Le guest ne fait plus de requête HTTP au moment où apt/dpkg écrivent dans le cache, ce qui écarte le syscall en échec à l'origine de `201::URIDone (14: Bad address)`. Repli automatique sur l'installation en ligne si le cache est incomplet.
- **Alpine** : HTTPS reste prioritaire ; en cas d'échec de vérification de certificat (symptôme ARMv7), une seule tentative est faite avec le dépôt en HTTP. Les paquets restent vérifiés par la signature `apk` — seule l'authentification du transport est abandonnée, et les deux sorties sont conservées dans le journal.
- **Diagnostics** : la sortie de PRoot n'est plus tronquée aux 4 000 derniers caractères (les messages du moteur sont écrits en début de sortie et étaient systématiquement coupés). Journal complet écrit dans `files/nemesis-linux/logs/install-<distro>.log`, affiché dans le message d'erreur et lisible depuis l'application via le bouton **Journal d'installation**.
- **Test des primitives PRoot** (`write`, `read`, `chmod`, `chown`, `rename`, `symlink`, DNS, HTTPS) exécuté avant l'installation des paquets : si une primitive fondamentale échoue, l'installation s'arrête en nommant la primitive fautive.
- **Reprise** : le rootfs n'est plus supprimé après un échec ; le nouvel essai reprend au stage des paquets sans retélécharger l'archive, et un rootfs extrait mais non finalisé est repris au lieu d'exiger une suppression manuelle.
- 58 tests Python réussissent (`tests/test_mobile_agent.py`), dont 8 nouveaux sur le chemin hors-ligne, la reprise, le cache, le test de primitives et le journal.

## v0.1.0-beta.14 — correctif de pacing et de flux

- L’intervalle normal de 8 s est attendu localement ; il ne déclenche plus un HTTP 429 ni l’échec du second message.
- Le cooldown après un vrai challenge de sécurité reste explicite et non rejouable.
- Les erreurs pré-soumission sont retournées directement avec leur code au lieu du faux résumé « Échec après N tentatives ».
- Tests de pacing et de propagation SSE ajoutés.


## Correctif — blocage des comptes DeepSeek et déconnexions de session (branche `fix/deepseek-account-blocking`)

- Un refus de vérification de sécurité (PoW) du provider est désormais un **code d'erreur explicite** (`security_challenge`, HTTP 429). Il n'est **plus rejoué automatiquement** : le client Python ne retente plus ces requêtes, et le moteur applique un refroidissement (3 min, puis 15 min après trois refus).
- Arrêt immédiat sur session expirée (`login_required`, HTTP 401) : aucune requête n'est envoyée au provider sans session authentifiée, et la copie de session périmée est abandonnée.
- **Cadence d'envoi** : deux envois ne s'enchaînent pas à moins de 8 s.
- Correction de la **restauration de session** : les cookies n'étaient réinjectés qu'en comparant la longueur des chaînes, ce qui écrasait une session valide par une copie plus courte et déconnectait le compte. La comparaison se fait désormais par nom de cookie, la copie est limitée à 12 h et n'est jamais réinjectée au-dessus d'un cookie vivant.
- **Identité du navigateur** : le User-Agent annonce désormais la version de Chrome réellement installée (au lieu de `Chrome/126` figé) et un mode `android` cohérent avec l'appareil est disponible (`POST /api/identity`). Le hook réseau n'est plus installé que sur l'origine du provider.
- Le hook réseau se rend **indétectable** par une simple vérification `toString` de `fetch` / `XMLHttpRequest` (marquage « native code »).
- Diagnostics en lecture seule : `GET /api/debug/fingerprint` (empreinte navigateur lue dans la page), journalisation unique des en-têtes envoyés au provider, et mesure du délai challenge PoW → complétion. Page de comparaison : `assets/web/fingerprint.html`.
- Un refus de vérification de sécurité déclenche une **notification** dans NEMESIS : l'utilisateur vérifie la session, aucun renvoi automatique n'est déclenché.


## v1.0.0 — première release 1.0

- Nouvelle interface Agent / Code, projets, historique et automatisations locales.
- Environnements de développement avec PRoot et outils terminal embarqués, en complément de SSH distant.
- Outils Android natifs et actions d’accessibilité activées explicitement par l’utilisateur.
- Option TokenHarbor avec clé éphémère en mémoire, test du catalogue et retour à DeepSeek.
- Améliorations des workspaces, de l’aperçu Web intégré, des pièces jointes et des tests d’intégration Android.
- Le connecteur GitHub de la bêta n’est pas inclus; les anciens identifiants GitHub correspondants sont effacés sans effacer volontairement les autres données NEMESIS.

### Vérification et limites

- `versionCode 11`, `versionName 1.0.0`; Android 8.0+ et ABIs ARMv7, ARM64, x86 et x86_64.
- APK debug, tests Android et APK release compilés; l’APK release garde l’identité de développement des bêtas pour permettre la mise à jour. **Ce n’est pas une clé de production.**
- 73 tests Python passent; tests DOM/flux/pièces jointes JavaScript, tests unitaires NemApi et tests synthétiques TokenHarbor réussis.
- Aucun appel réel de chat TokenHarbor n’est revendiqué.
- Les tests instrumentés ne sont pas exécutés sur l’émulateur contenant les données à préserver. PRoot est inclus mais son installation/exécution complète n’est pas validée; un smoke test Alpine précédent s’est arrêté sur un refus de permission pendant `apk add`. Aucun essai réel Google OAuth, ni appareil physique, n’est revendiqué.
- `targetSdk 28` maintenu pour le sideload et le stockage historique; pas de distribution Google Play.
