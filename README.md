# NEMESIS Android

NEMESIS est une application Android native qui réunit un agent conversationnel, un environnement de programmation et des outils d’interaction Android. L’agent utilise par défaut DeepSeek via NemApi. **Une connexion fournisseur et Internet sont nécessaires** : NEMESIS n’est pas un modèle hors ligne.

> **v1.0.0 — première release 1.0 distribuée par sideload GitHub.** TokenHarbor est une option expérimentale désactivée par défaut. Son appel réel de chat n’a pas encore été validé pour cette release. Les fonctions connectées dépendent du compte, des autorisations et des services tiers.

## Installation et compatibilité

- Android **8.0 / API 26 ou ultérieur**. APK universelle : `arm64-v8a`, `armeabi-v7a`, `x86_64` et `x86`.
- Télécharger `NEMESIS-v1.0.0.apk` depuis [GitHub Releases](https://github.com/ekouenem/Nemesis-apk/releases/tag/v1.0.0).
- Distribution sideload, **pas une publication Google Play**. `targetSdk 28` est conservé pour le contrat historique du dossier `/sdcard/Nemesis`; une diffusion Play nécessite une migration dédiée.
- Pour que les installations beta.9 puissent être mises à jour, la release garde la même identité de signature de développement que les versions précédentes. **Ce n’est pas une clé de production.** Sauvegardez les fichiers importants; désinstaller l’application peut supprimer ses données privées.

Au premier lancement, connectez-vous à DeepSeek depuis l’interface intégrée. Une reconnexion ou un CAPTCHA peut être nécessaire. La session dépend également des règles du site et d’Android.

## Fonctions

- **Agent** : recherche Web, mémoire, fichiers par discussion, lecture de documents, skills et outils MCP.
- **Code** : workspaces par discussion/projet, outils de fichiers, terminal Linux local via PRoot lorsque la distribution est installée, ou serveur SSH configuré explicitement.
- **Outils Android** : inventaire et actions d’applications via pont natif; les actions d’accessibilité requièrent l’activation explicite du service dans les réglages Android.
- **Projets et automatisations** : organisation des discussions et tâches planifiées localement. Android peut retarder ou arrêter les tâches selon ses règles de batterie, alarmes et arrière-plan.
- **MCP** : transports HTTP Streamable et stdio via SSH. Les outils agissent avec les permissions du serveur; utilisez seulement des serveurs de confiance.

Les outils de l’agent peuvent exécuter des commandes, écrire/supprimer des fichiers ou agir sur des services externes. Certains n’affichent pas de confirmation individuelle. Arrêter une tâche dans l’interface ne peut pas toujours annuler une requête déjà acceptée par un service tiers.

## Fournisseurs et connecteurs

### DeepSeek

DeepSeek reste le fournisseur par défaut. La disponibilité, les CAPTCHAs, les formats de pièces jointes et les conditions d’utilisation relèvent de DeepSeek. Une connexion valide et le réseau sont nécessaires.

### TokenHarbor (expérimental)

Dans **Paramètres → Moteur**, une clé TokenHarbor peut être saisie et testée sur `/v1/models`. La sélection vise `deepseek-v4.1-flash:free`. La clé n’est pas écrite dans les préférences, fichiers, notes ou APK; elle demeure en mémoire du processus tant que l’option est activée et doit être saisie à nouveau après fermeture/redémarrage. L’action d’effacement revient à DeepSeek. L’accès au modèle gratuit, sa disponibilité et ses limites dépendent du compte et de TokenHarbor. Les tests synthétiques vérifient le flux d’authentification et la non-persistance, **pas un appel réel de chat réussi**.

### Google Workspace

L’autorisation Google est distincte de la session DeepSeek et dépend de la configuration OAuth, des APIs activées, du compte et de sa politique Workspace. Les outils Drive/Docs/Sheets doivent être activés explicitement pour une discussion. Les tests synthétiques ne garantissent pas qu’une configuration Google réelle fonctionne.

### GitHub

Le connecteur GitHub des versions bêta précédentes n’est pas inclus dans v1.0.0. Les identifiants GitHub historiques correspondants sont effacés lors de l’initialisation; les autres données NEMESIS ne sont pas volontairement supprimées.

## Données et confidentialité

Les préférences, discussions et workspaces sont conservés dans le stockage privé de l’application. `/sdcard/Nemesis` peut servir aux fichiers partagés selon les permissions Android; d’autres applications autorisées peuvent y accéder. Les cookies DeepSeek restent dans le stockage WebView soumis aux règles Android. Sauvegardez vos fichiers avant une mise à jour importante.

Une clé TokenHarbor activée est gardée en mémoire du processus, mais demeure un secret : ne la partagez pas. DeepSeek, TokenHarbor, Google, les serveurs MCP/SSH et les services de recherche appliquent leurs propres politiques de traitement des données.

## Vérification et limites connues

- `versionName 1.0.0`, `versionCode 11`; Android 8.0+ et quatre ABIs.
- APK debug, APK de test Android et APK release compilées; cette dernière est signée avec la même clé de développement pour permettre une mise à jour. Ce n’est pas une signature de production.
- Tests Python : **73 passés**. Tests JavaScript de disponibilité DOM, flux/continuation et pièces jointes, ainsi que tests unitaires NemApi, réussis.
- Les tests synthétiques TokenHarbor vérifient configuration éphémère, authentification du catalogue/modèle, non-persistance, compatibilité avec une ancienne URL DeepSeek et effacement. Aucun essai réel de chat TokenHarbor n’est revendiqué.
- Les tests instrumentés Android n’ont pas été lancés sur l’émulateur de travail afin de préserver ses données existantes. PRoot est compilé et inclus, mais son installation/exécution complète n’est pas validée : un smoke test Alpine antérieur s’est arrêté sur un refus de permission pendant `apk add`. La prise en charge dépend des ABI, de l’espace disque, de la version Android, du noyau et des permissions.
- `targetSdk 28` est conservé temporairement pour le sideload et le stockage historique; Google Play n’est pas pris en charge par cette release.

## Développement

JDK 17+, Android SDK 34 et Python sont requis. Dans le Codespace du projet :

```bash
export ANDROID_HOME=/workspaces/android-sdk
export JAVA_HOME=/usr/local/sdkman/candidates/java/21.0.13.0+2-ms
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
./gradlew --no-daemon --offline :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease
```

L’APK est générée dans `app/build/outputs/apk/release/`. Les tests connectés installent des APK sur l’appareil : ne les lancez pas sur un appareil contenant des données à préserver sans sauvegarde et accord explicite. `credentials.txt`, clés API, cookies, keystores privés et données utilisateur ne doivent jamais être ajoutés à Git.

## Licences et crédits

Le provider et le parseur adaptés de NEMESIS-AGENT proviennent du commit `55e8daffb27c6da23274b86cf63d1524b312edb7`, licence MIT : voir [LICENSE-NEMESIS](LICENSE-NEMESIS). NemApi conserve sa licence upstream dans `nemapi/LICENSE-UPSTREAM`. Les sources et licences des skills tiers figurent dans [SKILLS-SOURCES.md](SKILLS-SOURCES.md) et `third_party/`. Les sources et licences des bibliothèques natives PRoot/talloc/libandroid-shmem sont répertoriées dans [THIRD_PARTY-NATIVE.md](THIRD_PARTY-NATIVE.md), avec les archives sources correspondantes. Les conditions des services externes restent applicables.
