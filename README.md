# NEMESIS Android

NEMESIS est une application Android native dont **le moteur principal reste NemApi intégré**. Le fournisseur DeepSeek reste géré par l’intégration NemApi, comme dans la beta.9. Il n’y a pas de sélecteur de fournisseur ni de clé TokenHarbor dans l’application. Une connexion DeepSeek et Internet sont nécessaires; NEMESIS n’est pas un modèle hors ligne.

> **NEMESIS v1.0.0** reprend la continuité de la beta.9 : NemApi reste le moteur principal, les paramètres NemApi/DOM d’origine sont accessibles, et la discussion de démonstration n’est plus créée.

## Installation et compatibilité

- Android **8.0 / API 26 ou ultérieur**. APK universelle : `arm64-v8a`, `armeabi-v7a`, `x86_64` et `x86`.
- Télécharger `NEMESIS-v1.0.0.apk` depuis [GitHub Releases](https://github.com/ekouenem/Nemesis-apk/releases/tag/v1.0.0).
- Distribution sideload, **pas une publication Google Play**. `targetSdk 28` est conservé pour le contrat historique du dossier `/sdcard/Nemesis`; une diffusion Play nécessite une migration dédiée.
- `versionCode 12` et même identifiant/certificat de signature que beta.9 et l’APK retirée, afin de permettre une mise à jour par-dessus sans désinstaller l’application. **La clé de signature est une clé de développement, pas une clé de production.** Ne désinstallez pas l’application et n’effacez pas ses données pour effectuer la mise à jour.

L’application utilise les données privées existantes. La migration de démarrage ne supprime que la discussion d’aperçu synthétique intégralement intacte si elle est encore présente; les discussions utilisateur ne sont pas supprimées ni renommées. La compilation de vérification n’inclut pas de démonstration auto-créée.

## Fonctions

- **Agent** : moteur NemApi/DeepSeek, recherche Web, mémoire, fichiers par discussion, lecture de documents, skills et outils MCP.
- **Code** : workspaces par discussion/projet, outils de fichiers, terminal Linux local via PRoot lorsque la distribution est installée, ou serveur SSH configuré explicitement.
- **Outils Android** : inventaire et actions d’applications via pont natif; les actions d’accessibilité requièrent l’activation explicite du service dans les réglages Android.
- **Projets et automatisations** : organisation des discussions et tâches planifiées localement. Android peut retarder ou arrêter les tâches selon ses règles de batterie et d’arrière-plan.
- **MCP** : transports HTTP Streamable et stdio via SSH. Les outils agissent avec les permissions du serveur; utilisez seulement des serveurs de confiance.

Les outils de l’agent peuvent exécuter des commandes, écrire/supprimer des fichiers ou agir sur des services externes. Certains n’affichent pas de confirmation individuelle. Arrêter une tâche dans l’interface ne peut pas toujours annuler une requête déjà acceptée par un service tiers.

## Fournisseurs et connecteurs

### NemApi / DeepSeek

NemApi reste le moteur principal et l’unique route de l’agent. DeepSeek reste le fournisseur intégré configuré via NemApi. Les connexions, limites, CAPTCHAs, formats de pièces jointes et conditions d’utilisation relèvent de DeepSeek.

### Google Workspace

L’autorisation Google est distincte de la session DeepSeek et dépend de la configuration OAuth, des APIs activées, du compte et de sa politique Workspace. Les outils Drive/Docs/Sheets doivent être activés explicitement pour une discussion. Les tests synthétiques ne garantissent pas qu’une configuration Google réelle fonctionne.

Le connecteur GitHub natif des versions bêta n’est pas proposé comme moteur ou fournisseur; les commandes GitHub peuvent être utilisées dans un environnement Linux/SSH que l’utilisateur a configuré. Aucune clé API de fournisseur alternatif n’est demandée par NEMESIS.

## Affichage Chromebook et tablette

En mode écran large, le panneau de discussion occupe désormais un seul conteneur extensible. La correction retire l’ancien conteneur vide qui se partageait la hauteur avec le fil et repoussait son contenu dans la moitié basse de la fenêtre. Le build a été vérifié; aucun test sur le Chromebook de l’utilisateur n’a été effectué.

## Données et confidentialité

Les préférences, discussions et workspaces sont conservés dans le stockage privé de l’application. `/sdcard/Nemesis` peut servir aux fichiers partagés selon les permissions Android; d’autres applications autorisées peuvent y accéder. Les cookies DeepSeek restent dans le stockage WebView soumis aux règles Android. Sauvegardez les fichiers importants; ne désinstallez pas pour appliquer une mise à jour.

Les clés/API, cookies, serveurs MCP/SSH et services de recherche impliquent leurs propres politiques de traitement. NEMESIS n’inscrit aucune clé TokenHarbor dans ses préférences, fichiers, notes ou APK; il n’y a pas de champ TokenHarbor dans la v1 (essai interne).

## Vérification et limites connues

- `versionName 1.0.0`, `versionCode 12`; Android 8.0+ et quatre ABIs.
- APK debug, AndroidTest et release compilées; mise à jour installée sur l’émulateur API 27 sans désinstallation. Certificat de développement identique aux versions bêta pour préserver la mise à jour des données. Ce n’est pas une signature de production.
- Tests Python : **74 passés**. Tests JavaScript de disponibilité DOM, flux/continuation et pièces jointes, et tests unitaires NemApi passés.
- Le test de migration vérifie que seul l’aperçu synthétique intact est retiré et qu’une discussion utilisateur est conservée.
- Tests instrumentés non lancés sur l’émulateur contenant les données à préserver. Aucun test physique Chromebook n’est revendiqué. PRoot est inclus mais son installation/exécution complète n’est pas validée : un smoke test Alpine précédent s’était arrêté sur un refus de permission pendant `apk add`.
- Aucun essai OAuth Google ni requête DeepSeek réelle n’est revendiqué pour cette correction. `targetSdk 28` est conservé pour le sideload; Google Play n’est pas pris en charge.

## Développement

JDK 17+, Android SDK 34 et Python sont requis. Dans le Codespace du projet :

```bash
export ANDROID_HOME=/workspaces/android-sdk
export JAVA_HOME=/usr/local/sdkman/candidates/java/21.0.13.0+2-ms
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
./gradlew --no-daemon --offline :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease
```

L’APK v1.0.0 publiée : `NEMESIS-v1.0.0.apk`. La sortie de build est dans `app/build/outputs/apk/release/`. Les tests connectés installent des APK sur l’appareil : ne les lancez pas sur un appareil contenant des données à préserver sans sauvegarde et accord explicite. `credentials.txt`, clés API, cookies et keystores privés ne doivent jamais être ajoutés à Git.

## Licences et crédits

Le provider et le parseur adaptés de NEMESIS-AGENT proviennent du commit `55e8daffb27c6da23274b86cf63d1524b312edb7`, licence MIT : voir [LICENSE-NEMESIS](LICENSE-NEMESIS). NemApi conserve sa licence upstream dans `nemapi/LICENSE-UPSTREAM`. Les sources/licences des skills figurent dans [SKILLS-SOURCES.md](SKILLS-SOURCES.md) et `third_party/`. Les sources/licences PRoot, talloc et libandroid-shmem sont répertoriées dans [THIRD_PARTY-NATIVE.md](THIRD_PARTY-NATIVE.md). Les conditions des services externes restent applicables.
