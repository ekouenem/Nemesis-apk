# Contexte de reprise — NEMESIS Android

État mis à jour le 11 novembre 2026. Ce document est un instantané : vérifier Git, la release et l’environnement avant toute intervention. Il ne contient aucun identifiant, jeton, cookie ni clé privée.

## Version de référence

La présente itération vise la préversion **v0.1.0-beta.6**, succédant à beta.5, sans modifier les anciens tags. Vérifier le tag et les notes GitHub pour le commit exact et le SHA-256 de l’APK publiée. `versionCode 7`, `versionName 0.1.0-beta.6`, `minSdk 26`, ABIs `armeabi-v7a` et `x86`. Certificat de signature debug SHA-256 `ac7c51518c090e438392874c77a6968d7cb16ca711850e6b068dee67188e9347`.

- Dépôt de travail : `ekouenem/Nemesis-apk`, branche `main` ; ne rien pousser dans le dépôt NemApi-Apk d’origine.
- Beta.4 : commit `d5e9a1c42b1b8fe48e2519bc7b51136ecd21d01d`, SHA APK `b5b2b244f07a150e600879060f50c94abf78b00b967211de5d640336b2a437ce` ; sa release reste inchangée.
- Codespace utilisé : `nemesis-android-2-coeurs-wvxrrqp9x7g5fgx75`, `/workspaces/Nemesis-apk`, 2 cœurs, 8 Go ; SDK `/workspaces/android-sdk`, JDK 21 `/usr/local/sdkman/candidates/java/21.0.13.0+2-ms`. Vérifier son existence et l’accès : le Codespace peut être arrêté ou supprimé.

## Architecture réelle

Une APK Android native intègre NemApi et l’adaptation Python 3.11 / Chaquopy de NEMESIS-AGENT ; le provider, pas le modèle, est local. Le fournisseur est DeepSeek via WebView. L’ancien plan de documentation Kotlin/Koog, interface WebView générale et exécuteurs Termux/cloud ne décrit pas l’implémentation livrée. L’interface de NEMESIS est native ; la WebView sert au fournisseur. La boucle Python d’origine est adaptée progressivement, pas entièrement portée.

Entrées importantes : `app/src/main/java/com/nemesis/apk/MainActivity.kt` (interface), `JobRunner.kt`, `NemesisService.kt`, `RuntimeRecoveryJob.kt`, `SshBridge.kt`, ponts MCP ; `app/src/main/python/mobile_agent.py`, `providers/nemapi_v3.py`, `action_parser.py`, `mcp_client.py`, `skill_store.py` ; `nemapi/app/src/main/java/com/nemap/android/api/NemesisSession.kt`, `ApiServer.kt`, `core/DeepSeek.kt`, `web/Engine.kt`, `web/WebViewHost.kt` et `nemapi/app/src/main/assets/js/adapter-deepseek.js`. Parcourir le code avant de modifier ces composants.

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
- Le flux automatique de continuation longue reste **non validé de bout en bout**. Sur un cas réel, `element.click()` n’a pas déclenché la continuation ; un événement tactile Android a déclenché `POST /api/v0/chat/continue`. CAPTCHA puis blocage de l’environnement ont empêché une validation complète. Ne pas masquer ou contourner un challenge et ne pas multiplier les prompts.
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
3. Priorités techniques possibles à la demande : validation ARM 32 bits ; validation prudente de la continuation longue ; finalisation progressive des outils Code d’origine ; tests supplémentaires des formats de pièces jointes et de la reprise après interruption.
4. Éviter les tests artificiels et appels DeepSeek répétés : un compte précédent a été suspendu. Informer régulièrement l’utilisateur et distinguer les tests synthétiques, les tests réels et les limites.

Sujet séparé : le transfert direct du fork `nemesishighschool-cloud/NemApi-Apk` vers `teteekoue` a été refusé ; une copie indépendante `NemApi-Apk-standalone` a été créée et son transfert demandé. L’acceptation n’a pas été confirmée ; vérifier l’état avant toute action supplémentaire.
