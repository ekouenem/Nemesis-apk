# Contexte de reprise — NEMESIS Android

État au 13 novembre 2026. Ce document décrit la validation beta.9; vérifier Git et la release avant toute action. Aucun secret, token, cookie ou clé privée ne doit être ajouté au dépôt.

## Version publiée

- Release : **[v0.1.0-beta.9](https://github.com/ekouenem/Nemesis-apk/releases/tag/v0.1.0-beta.9)** ; APK : `NEMESIS-v0.1.0-beta.9.apk` ; SHA-256 : `f04b43f3539cdd70c6f632b807a4b26922f05b4fdd40e4052436fa60ddec4a94` (identique au digest de l’asset GitHub). L’APK est signée avec la clé de développement, non une clé de production.
- `versionCode 10`, `versionName 0.1.0-beta.9`, `minSdk 26`, quatre ABIs Chaquopy (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`). L’APK release est signée avec le même certificat de développement que beta.8 (`ac7c51518c090e438392874c77a6968d7cb16ca711850e6b068dee67188e9347`) pour les mises à jour par-dessus; ce n’est pas une clé de production.
- `targetSdk 28` est conservé temporairement pour la compatibilité avec `/sdcard/Nemesis`; la release est un sideload GitHub, **pas une soumission Google Play**. La seule règle lint désactivée est `ExpiredTargetSdkVersion`; une future soumission Play exige migration stockage/target SDK.
- Dépôt `ekouenem/Nemesis-apk`, branche `main`, Codespace `nemesis-android-2-coeurs-wvxrrqp9x7g5fgx75`, chemin `/workspaces/Nemesis-apk`. Le dépôt NemApi-Apk d’origine ne doit pas être utilisé pour pousser cette application.

## Modifications beta.9

- **Moteur DOM** : minuterie posée au dispatch réel vers WebView; délais croissants pour les seules observations idempotentes; état de santé après trois délais consécutifs; redémarrage manuel qui ne renvoie pas de message. Les actions d’envoi/clic ne sont pas rejouées. WebView reconstruction après perte de renderer conservée.
- **Shell** : PRoot/Debian entièrement retiré de l’APK, des sources de runtime et des réglages. Bash et SFTP utilisent uniquement SSH configuré; le workspace local Android n’est pas automatiquement monté sur l’hôte distant. Sortie Bash plafonnée à 512 Kio.
- **Connecteurs expérimentaux** : GitHub PAT nettoyé des espaces accidentels, chiffré Android Keystore, état UI distinguant configuration et accès vérifié; chemin de test `/user`. Google OAuth est natif (pas dans WebView), état de compte distinct d’un test réussi et chemin de test Drive. Lectures Drive limitées à 200 Kio et types texte/Docs/Sheets. Tests synthétiques seulement; aucun accès utilisateur réel n’est validé.
- **Compatibilité** : ABI x86/ARMv7 précédente élargie aux quatre ABIs Chaquopy. La clé de développement n’a pas été copiée dans Git; seule l’APK asset est publiée.

## Vérifications réellement effectuées

- `PYTHONPATH=app/src/main/python python3 -m unittest discover -s tests -v` : **74/74** tests Python, incluant mocks GitHub/Google, état par discussion, accès API simulé et limites de lecture.
- Cinq suites JavaScript passent : readiness, DOM adapter, transfert 50 MiB synthétique, hook et continuation.
- NemApi JVM : **13 tests** passent, dont quatre `DomHealthTest`.
- Android instrumenté sur AVD existant `nemesis-api27-32bit`, x86, Android 8.1/API 27 : **2 tests DOM**, **4 tests parsing SSH**, **2 tests Android Keystore/connecteur** passent. L’application beta.9 debug a été installée avec `adb install -r`; écran d’accueil/Paramètres sans crash et absence du menu PRoot vérifiées. Aucun wipe ni désinstallation; le même AVD a été redémarré après déplacement temporaire des caches build, données laissées intactes.
- Build réussi : `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, `:app:assembleRelease`; APK release alignée/signée puis vérifiée par `apksigner`. SHA-1/SHA-256 du certificat identiques à beta.8. `git diff --check` propre avant publication.

## Limites non validées — ne pas présenter comme testées

- **DeepSeek réel** : aucune conversation, connexion ou requête de production lancée; les tests DOM sont synthétiques sur `about:blank`. Pas de validation sur appareil ARM physique. Aucun auto-redémarrage ne garantit la stabilité absolue; après plusieurs timeouts, vérifier la discussion avant reprise.
- **Google Workspace réel** : aucun compte OAuth relié à l’émulateur et aucune requête API authentifiée. Les deux tests Android vérifient uniquement métadonnées et persistance de compte synthétiques; les API Google `GoogleSignIn`/`GoogleAuthUtil` génèrent des avertissements de dépréciation. Le propriétaire doit activer Drive/Docs/Sheets APIs et enregistrer OAuth Android pour `com.nemesis.apk` + SHA-1 de signature.
- **GitHub réel** : aucun PAT d’utilisateur configuré dans NEMESIS; test API live non effectué. Le secret Codespace conservé hors dépôt n’a pas été réutilisé comme credential du connecteur.
- **SSH distant réel** : l’endpoint parser passe; aucun serveur SSH utilisateur connecté lors de cette validation. Le premier usage conserve TOFU sans authentification indépendante de l’empreinte; Codespaces ne sont pas pris en charge par le transport direct par mot de passe.
- Les tests 50 MiB sont synthétiques; ne prouvent pas une upload réelle de cette taille. Aucun essai utilisateur ARMv7 n’a été fait.

## Publication

L’utilisateur a explicitement autorisé commit, push et nouvelle release beta.9. Publiée en préversion GitHub; le tag pointe sur `4e626cf070a79fc13e34952df5c831ba9994582a` et l’asset a été vérifié avec le SHA-256 ci-dessus. Aucun secret ou keystore n’est dans le commit.

## Historique — itération UI noVNC et streaming (antérieure à la validation beta.9)

- Le skill tiers `wshobson/agents`, skill `mobile-android-design`, a été cloné hors dépôt dans `/workspaces/mobile-android-design-skill` (commit `9c61ab2`); `SKILL.md` et `references/details.md` ont été lus. Les recommandations sont adaptées à l’interface Android native existante, sans conversion Compose.
- `MainActivity.kt` : sélecteur Chat/Work/Code avec Work centré par défaut, navigation par toucher et balayage; le + et la nouvelle discussion du tiroir arrivent au même écran. Le mode actif est maintenant une capsule claire avec texte bleu au premier plan (pas de calque bleu opaque par-dessus le libellé). Icône Workspace dans les fils uniquement en Work/Code. Import Workspace copie le fichier dans le dossier de session (100 Mo max, noms nettoyés/doublons préservés), sans pièce jointe ni envoi automatique.
- Icône Android du launcher en adaptive icon; les logos dans l’interface ne sont pas changés.
- Duplication du flux : `SessionProvider` sauvegardait le texte du tour dans `live_progress` alors que `_send` persistait le même texte avant les cartes d’outil; la révision reconstruisait alors bulle Markdown + carte + ancien texte brut. `_send` vide maintenant l’aperçu par requête avant persistance; le texte de streaming restant passe par Markwon. Test de régression simulé `test_live_preview_is_cleared_before_persisted_prose_and_tool`, sans réseau/DeepSeek réel.
- Validation finale : 69 tests Python, 5 suites JavaScript, build `:app:assembleDebug` réussi; `git diff --check` propre. Aucun build release. Pas de test instrumenté ou vérification écran : aucun appareil ADB n’est présent.
- noVNC redémarré réellement (x11vnc/websockify/proxy remplacés); port Codespaces 6082 vérifié **private**, HTTP local 200. Ports 5900 et 6080 restent loopback. Lien privé : `https://nemesis-android-2-coeurs-wvxrrqp9x7g5fgx75-6082.app.github.dev/vnc.html?autoconnect=true&resize=scale`.
- Limite : `nemesis-api27-32bit` avorte au démarrage malgré KVM utilisable. ADB ne trouve aucun émulateur; ne pas déclarer le noVNC comme aperçu Android fonctionnel tant que l’AVD ne démarre pas. L’AVD alternatif n’a pas été modifié (exigeait ~7.4 Go, libres ~2.2 Go). N’effacer aucune image/donnée AVD.
- APK debug local dans Codespace: `app/build/outputs/apk/debug/app-debug.apk`, SHA-256 `b557e6ce4487493251cda0cba8d156d66b7b6877729bf9f6c205cf3d58712a0a`. Dans cette étape aucun binaire n’a été proposé ou copié. Aucun commit/push/release.

## Architecture réelle

Une APK Android native intègre NemApi et l’adaptation Python 3.11 / Chaquopy de NEMESIS-AGENT ; le provider, pas le modèle, est local. Le fournisseur est DeepSeek via WebView. L’ancien plan de documentation Kotlin/Koog, interface WebView générale et exécuteurs Termux/cloud ne décrit pas l’implémentation livrée. L’interface de NEMESIS est native ; la WebView sert au fournisseur. La boucle Python d’origine est adaptée progressivement, pas entièrement portée.

Entrées importantes : `app/src/main/java/com/nemesis/apk/MainActivity.kt` (interface), `JobRunner.kt`, `NemesisService.kt`, `RuntimeRecoveryJob.kt`, `SshBridge.kt`, ponts MCP ; `app/src/main/python/mobile_agent.py`, `providers/nemapi_v3.py`, `action_parser.py`, `mcp_client.py`, `skill_store.py` ; `nemapi/app/src/main/java/com/nemap/android/api/NemesisSession.kt`, `ApiServer.kt`, `core/DeepSeek.kt`, `web/Engine.kt`, `web/WebViewHost.kt` et `nemapi/app/src/main/assets/js/adapter-deepseek.js`. Parcourir le code avant de modifier ces composants.

## Changements et vérifications beta.8

- Après une utilisation sur téléphone ARM 32 bits, l’utilisateur a signalé des lenteurs et des messages « ne répond pas », alors que les versions précédentes et son Chromebook x86 n’avaient pas montré ce problème. L’APK beta.7 contenait déjà `armeabi-v7a` et `x86` ; un défaut propre à l’ABI n’est pas démontré.
- Deux blocages ont été reproduits sur x86 avec des lectures artificiellement retardées : observation pendant le streaming et chargement d’une discussion. En beta.8, ces lectures et le décodage de la session s’effectuent en arrière-plan ; les résultats périmés ne remplacent pas une discussion plus récente. L’aperçu du flux est borné, l’historique se charge par tranches, et les messages de plus de 8 000 unités UTF-16 sont présentés par parties en texte brut, avec conservation et copie du texte complet. Cela change le rendu Markdown des textes volumineux, pas le contenu stocké. Les actions ne sont pas rejouées.
- 58 tests Python, tests JavaScript, neuf unitaires NemApi, 17 tests Android x86 hors réseau réussis. Réponse synthétique de plus de 100 000 caractères, Unicode, copie complète, ancien historique, navigation concurrente, garde Continue et timeout DOM inclus. Mise à jour beta.7 → beta.8 sur l’émulateur x86 : discussion, URL, état incertain et fichier privé de test conservés. Le test 50 Mio reste synthétique.
- L’APK unique inclut x86 et ARMv7 32 bits ; les bibliothèques natives et extensions Python de chaque ABI ont été contrôlées en ELF 32 bits et la signature correspond à beta.7. Aucun test du correctif sur le téléphone utilisateur ni sur un émulateur ARMv7 Android 8.1. L’image Android 8.1 ARM64 officielle a démarré sous émulateur ancien sur hôte x86, mais est 64 bits uniquement (`abilist32` vide), donc incapable d’installer l’APK 32 bits. Ne pas présenter cela comme un test ARMv7 réussi. Aucune requête DeepSeek réelle.

## Changements et vérifications beta.7

- L’utilisateur signalait une interruption à l’apparition de « Continue » et un délai du moteur DOM. La course d’un ancien bouton a été reproduite en WebView Android locale avant correction, puis le test a réussi. `ContinueGate.kt` distingue le bouton déjà utilisé du suivant ; l’identifiant de la cible est revérifié avant le clic natif. Si l’ancien bouton reste visible sans transition identifiable, l’application signale une erreur plutôt que de risquer un double clic.
- `Engine.kt` ne relance que des lectures DOM autorisées et seulement sur délai, jamais un envoi ni un clic. `hook.js` et `DeepSeek.kt` transfèrent le texte incrémentalement ; `MainActivity.kt` limite le travail d’affichage et `mobile_agent.py` utilise une révision des messages. Le contenu reçu avant une interruption est enregistré comme partiel, sans exécuter d’actions depuis ce texte ; l’état de la session peut rester incertain.
- Validation : 57 tests Python, tests JavaScript, neuf tests unitaires NemApi et 11 scénarios Android locaux hors réseau réussis sur Android 8.1 x86, dont délai simulé, deux continuations et bouton persistant ; APK installée, signature et fichier de release vérifiés. La recherche Web a réussi dans un groupe précédent avec accès réseau ; son test a échoué normalement durant une exécution entièrement hors ligne et a été exclu du groupe hors ligne final. Le scénario WebView 50 Mio est synthétique.
- Un essai réel beta.6 avant correction a produit trois continuations et 92 298 caractères ; il ne prouve rien quant à la beta.7. Après corrections, aucune validation réelle DeepSeek concluante : un essai a reçu le code `40003`, et l’utilisateur indique que ses deux comptes sont bloqués. La cause exacte du blocage n’est pas établie. Ne pas annoncer que les erreurs sont définitivement résolues.
- L’utilisateur demande de continuer **sans requête DeepSeek ni nouveau compte**. Ne pas ouvrir ni restaurer automatiquement une page DeepSeek pendant les tests ; couper le réseau de l’émulateur pour les tests locaux susceptibles d’ouvrir la WebView, puis vérifier que les fixtures n’y naviguent pas. Les comptes, cookies et données restent à préserver. Aucun test ARM physique.

## Changements beta.6

- La notification de service ajoute « Fermer » : arrêt de l’écoute NemApi, du watchdog et du job périodique ; état explicitement arrêté persistant. Rouvrir l’activité réactive le service. Les requêtes ou commandes déjà engagées ne sont pas garanties interrompues. La WebView n’est pas effacée. Un test instrumenté vérifie notification, port, job et redémarrage.
- Script public [nemesis-termux-ssh](https://github.com/nemesishighschool-cloud/nemesis-termux-ssh) : installation OpenSSH, alias de connexion et mot de passe, serveur local, lancement pour une nouvelle session Bash interactive. Termux mono-utilisateur : pas de vrai nouveau compte isolé ; ne pas prétendre le contraire. Une commande via l’outil Bash NEMESIS et un rapatriement SFTP depuis le véritable Termux sur émulateur Android 8.1 x86 ont réussi. Pas de test ARM physique, ni garantie Android en arrière-plan.

## Changements beta.5

- Recherche locale à l’agent : Wikipédia fr et Bing, DuckDuckGo en repli ; le blocage anti-robot n’est pas contourné et les résultats peuvent être incomplets. Test Web réel effectué sur Codespace et Android x86.
- `read_file` local en Work et Code : texte, extraction simple DOCX/PPTX/XLSX, images JPEG/PNG/WebP et PDF transmis au provider. Audio/vidéo non analysés ; respect des limites et de l’acceptation de DeepSeek. Une tâche Work réelle a identifié les nombres d’une image et d’un PDF de test.
- Work/Code : durée totale au dernier message uniquement, sans votes ; outils locaux sans SSH. Avec permission, `Nemesis/Skills`, `Nemesis/Work/<titre>` et `Nemesis/Code/<titre>`, collisions distinguées et migration journalisée des anciens dossiers UUID sans écrasement. Les dossiers directement vides créés automatiquement peuvent être supprimés. En l’absence de permission, l’ancien repli privé commun demeure.
- Pièces jointes intégrées aux bulles et vignettes image/PDF, nouveau logo source dans `app/src/main/res/drawable-nodpi/`. PDF 50 Mo, autres fichiers 20 Mo, 64 Mo total, quatre pièces. Chemins locaux privés validés côté API et données transmises à la WebView en morceaux. 50 Mo ont été testés localement dans une WebView Android et en JavaScript sans envoi au fournisseur ; ne pas prétendre qu’un PDF réel de 50 Mo a été accepté par DeepSeek.
- `CHALLENGE_REQUIRED` est un refus du fournisseur, pas nécessairement une déconnexion. Défaut identifié : l’ancien bouton du navigateur rechargeait l’accueil, pouvant faire disparaître la page/boîte de vérification. Il ouvre désormais la page courante, et un bouton de vérification est disponible sur une session incertaine. Brève attente d’une éventuelle réaction de la page, sans renvoi automatique. Un refus réel ou invisible peut persister : sa disparition générale n’est pas démontrée.
- Validation : 53 tests Python, JavaScript et unitaires NemApi ; neuf tests Android 8.1 x86, dont transfert local 50 Mo et aperçu. Les tests réels multimodaux ont été limités. ARM 32 bits et continuation longue non validés.

## État fonctionnel et limites

- Chat, Work et Code ; streaming sans suivi forcé ; bouton de retour en bas ; copie, votes locaux et durée en Chat ; Markdown, pièces jointes, workspace, aperçu documents, recherche Web, SSH/SFTP, mémoire contrôlable, 18 skills tiers et MCP. Les détails et limites sont dans le README.
- Sessions DeepSeek par URL : ne pas renvoyer l’historique, le prompt système ou la mémoire lors d’une reprise. Une personnalisation ou une mémoire modifiée ne s’applique donc automatiquement qu’aux nouvelles discussions. Ne jamais rejouer un message potentiellement soumis ; état incertain = vérification et reprise explicite.
- Beta.4 : attente bornée du champ avant envoi, y compris URL déjà ouverte ; un nouvel essai de rédaction n’a lieu qu’avant soumission ; garde atomique d’URL dans l’adaptateur. `not_submitted` ne doit être émis que lorsque la non-soumission est établie. Le champ réel était détecté sur page stabilisée et la course corrigée est plausible, mais la cause de tous les `input_missing` utilisateur n’est pas démontrée.
- La continuation beta.7 reste **non validée de bout en bout avec DeepSeek réel après correctif**. Un essai réel beta.6 avant correctif a fonctionné trois fois mais n’établit pas la fiabilité de la nouvelle version. `element.click()` n’a pas déclenché la continuation dans un autre cas réel ; le toucher Android, lui, l’a déclenchée. Ne pas masquer ni contourner les contrôles de sécurité et ne pas envoyer de requêtes DeepSeek sans nouvel accord.
- Code ne possède pas encore tous les outils originaux ; les sous-agents et le patch multi-fichiers notamment manquent. Ne pas simuler les fonctions absentes. Mémoire automatique dépendante du modèle ; suivi de téléversement heuristique ; SSH TOFU non vérifié à la première connexion ; action/commande déjà lancée pas forcément interrompue par Stop.
- La notification du service de premier plan Android 8.1 reste nécessaire dans le modèle actuel ; aucune garantie permanente malgré le service et le watchdog. Google OAuth dans la WebView embarquée et transfert automatique des cookies depuis le navigateur système ne sont pas pris en charge.

## Vérifications effectuées pour beta.4

- 44 tests Python ; tests JavaScript de disponibilité, garde URL, fraîcheur DOM, hook réseau et continuation ; unitaires NemApi ; compilation APK et APK de test : réussis.
- Émulateur Android 8.1 x86 : `ProviderReadinessInstrumentedTest`, `InterfaceStreamingInstrumentedTest`, `RegressionInstrumentedTest` : trois tests réussis. Le test d’interface attend désormais la fin de l’animation avec une limite de 5 secondes.
- `SessionNavigationInstrumentedTest` exécuté avec opt-in : trois messages courts, deux conversations, reprise de la première après champ temporairement `readOnly` ; réussi. Cela ne reproduit pas tous les appareils ni la totalité des incidents réels.
- ABI ARM 32 bits présente, mais aucun test réel ARM. Pas de validation complète de continuation longue après correctif. Ne pas élargir les résultats au-delà de ces faits.

## Données et environnement sensibles

Les données de WebView, cookies, comptes, clés et sauvegardes ne doivent jamais être ajoutés à Git ni affichés. Sauvegardes observées hors dépôt : `/workspaces/.nemesis-private/nemesis-deepseek-session.tar`, `/workspaces/.nemesis-private/nemesis-before-workspace-upgrade.tar`, clé de signature `/workspaces/.nemesis-private/nemesis-debug-signing.keystore`. Ce sont des chemins dans le Codespace, non une garantie que les fichiers existent encore ; préserver de manière sûre la clé de signature pour les mises à jour. Une ancienne sauvegarde est dans l’ancien Codespace `nemesis-android-32-bit-4j4wg4rgvjp9hj776` : ne pas le supprimer avant vérification d’une récupération si elle est encore nécessaire.

Préserver les données de l’émulateur, ne pas utiliser `-wipe-data` et ne pas recréer l’AVD avec un script destructif. `/workspaces/android-setup/start-emulator.sh` démarrait l’AVD `nemesis-api27-32bit`. Ne jamais exposer directement le port noVNC sans protection. Si l’accès est requis, vérifier le lien réellement et limiter l’exposition.

## Points pour une prochaine session

1. Vérifier branche, tag, fichiers et état du Codespace ; sauvegarder les données et la clé de signature avant toute opération risquée. Ne pas présumer que des jetons antérieurs existent encore : demander un nouvel accès si nécessaire, sans les enregistrer dans le dépôt.
2. Demander la priorité à l’utilisateur plutôt que lancer automatiquement une campagne. Si `input_missing` persiste, recueillir les circonstances et diagnostics sans secrets, reproduire prudemment et ne pas supposer une déconnexion.
3. Priorités possibles à la demande : recueillir modèle Android et circonstances des ralentissements ; vérifier beta.8 sur appareil ARMv7 réel en préservant les données ; approfondir les tests locaux de continuation et d’interruption ; finaliser progressivement les outils Code. Une validation réelle DeepSeek exige un nouvel accord explicite et un compte disponible ; ne pas la lancer automatiquement.
4. L’utilisateur indique que ses deux comptes DeepSeek sont bloqués et demande de poursuivre sans autre compte ni requête. Informer régulièrement l’utilisateur et distinguer les tests synthétiques, les tests réels et les limites.

Sujet séparé : le transfert direct du fork `nemesishighschool-cloud/NemApi-Apk` vers `teteekoue` a été refusé ; une copie indépendante `NemApi-Apk-standalone` a été créée et son transfert demandé. L’acceptation n’a pas été confirmée ; vérifier l’état avant toute action supplémentaire.
