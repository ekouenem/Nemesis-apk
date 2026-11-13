# NEMESIS Android

NEMESIS est une application Android native qui réunit, dans une seule APK, une interface de conversation et une adaptation du moteur d’agent Python de NEMESIS-AGENT, reliées à une copie intégrée de NemApi-Apk. Le seul fournisseur intégré actuellement est **DeepSeek** : l’utilisateur doit s’y connecter dans l’application. L’API locale ne rend pas le modèle autonome ni utilisable hors ligne.

> **Préversion : v0.1.0-beta.9.** Cette préversion est publiée pour installation directe depuis GitHub, pas pour Google Play. Les fonctions Google Workspace et GitHub restent expérimentales tant qu’elles n’ont pas été validées avec un compte réel. Elle comprend les correctifs DOM locaux, les connecteurs avec un vrai contrôle d’accès Drive/GitHub, et aucun PRoot embarqué. Les appels OAuth/Workspace/GitHub authentifiés n’ont pas pu être testés avec un compte utilisateur dans cette session.

## Installation

- [Télécharger l’APK beta.9](https://github.com/ekouenem/Nemesis-apk/releases/download/v0.1.0-beta.9/NEMESIS-v0.1.0-beta.9.apk) · [Notes de version](https://github.com/ekouenem/Nemesis-apk/releases/tag/v0.1.0-beta.9)
- Android minimum : **8.0 / API 26**. ABIs incluses : `arm64-v8a`, `armeabi-v7a`, `x86_64` et `x86`; les essais de cette release ont été faits sur l’émulateur x86 Android 8.1 uniquement.
- APK de variante release, signée avec la même clé de développement que beta.8 pour permettre une mise à jour par-dessus ; ce n’est **pas** une clé de production. Sauvegardez les données. SHA-256 : `f04b43f3539cdd70c6f632b807a4b26922f05b4fdd40e4052436fa60ddec4a94`.
- Le `targetSdk` reste 28 afin de préserver le contrat historique d’accès à `/sdcard/Nemesis`. L’APK est destinée au sideload GitHub ; une soumission Google Play requiert une migration dédiée du stockage et du niveau cible.

Ouvrez l’application et configurez DeepSeek dans son interface intégrée. La session est conservée dans le stockage privé de la WebView quand Android le permet, mais une reconnexion ou un CAPTCHA peuvent être demandés. Google Workspace utilise une connexion OAuth native, distincte de la session DeepSeek, et ne lit pas automatiquement les cookies Google du navigateur système.

La notification « NEMESIS actif » propose **Fermer** : l’écoute du serveur NemApi et sa relance planifiée sont arrêtées. Ouvrir de nouveau NEMESIS les réactive. Une requête ou commande déjà lancée peut ne pas s’interrompre immédiatement. La WebView et ses données de session ne sont pas effacées. Android peut limiter les notifications et services en arrière-plan.

Pour utiliser Termux sur le même appareil, consultez le [script public d’installation SSH](https://github.com/nemesishighschool-cloud/nemesis-termux-ssh). Le serveur écoute localement par défaut ; le nom choisi est un alias du compte unique Termux, pas un compte isolé. La commande Bash et le transfert SFTP NEMESIS → Termux ont été testés sur émulateur Android 8.1 x86.

## Bêta.9 — changements et limites à connaître

- **DOM** : le délai d’une évaluation démarre après son dispatch au WebView; les observations DOM idempotentes ont des délais croissants et ne rejouent pas les soumissions. Trois délais consécutifs signalent le moteur comme muet; l’utilisateur peut demander un redémarrage sans renvoi du message. Vérifié par tests DOM synthétiques sur l’émulateur, pas par une conversation DeepSeek réelle ni sur un appareil ARM physique.
- **Shell** : Debian/PRoot est différé et n’est pas inclus dans cette APK. Bash et les transferts SFTP passent uniquement par le serveur SSH configuré; le workspace local Android n’est pas monté automatiquement sur ce serveur. Les sorties Bash SSH sont plafonnées à 512 Kio pour éviter une consommation mémoire sans borne.
- **Connecteurs** : les paramètres distinguent désormais « compte/token enregistré » d’un accès testé. GitHub vérifie `/user`; Google vérifie réellement l’API Drive. Les tests locaux couvrent l’isolation, le chiffrement, le contrôle de compte, les erreurs API et les limites de lecture, mais utilisent des mocks ou des identifiants synthétiques; aucun accès de compte réel n’a été validé.
- **Stabilité et architectures** : l’APK inclut les quatre ABIs supportées par Chaquopy. Sur cet émulateur, l’app a été mise à jour avec `adb install -r`, l’écran Paramètres vérifié et les tests DOM/Keystore exécutés sans effacer les données.

## Utilisation

- **Nouvelle discussion** : l’écran de départ propose un sélecteur horizontal Chat / Work / Code, réglé par défaut sur Work. On peut toucher un mode ou glisser horizontalement, puis lancer la discussion; le bouton + et l’action du tiroir utilisent le même écran.
- **Workspace** : l’icône de dossier apparaît uniquement dans les discussions Work et Code. Depuis la liste des fichiers, « Importer » copie le document dans le workspace choisi, sans le joindre ni l’envoyer automatiquement à l’agent; l’utilisateur peut ensuite demander explicitement à l’agent de le traiter.

- **Chat** : conversations, streaming, Markdown, mémoire, copie et appréciation locale des réponses ; la durée est affichée lorsqu’elle est disponible. Le bouton **↓** ramène en bas si du contenu se trouve plus bas. Le streaming ne force pas le défilement pendant la lecture. Pour limiter les blocages, l’historique se charge progressivement (bouton « Afficher les messages précédents »). Les messages de plus de 8 000 unités UTF-16 s’affichent par parties en texte brut ; la copie conserve le texte complet. Pendant un long flux, seul un aperçu de la fin est affiché, sans supprimer le contenu reçu.
- **Work** : recherche et lecture Web, fichiers locaux, shell distant SSH, skills et MCP, avec cartes d’outils discrètes et durée totale de chaque tâche terminée. Les fichiers locaux ne sont pas automatiquement présents sur l’hôte SSH.
- **Code** : outils Work et programmation supplémentaires; Bash et SFTP s’exécutent sur le serveur SSH configuré. L’intégration du moteur d’origine est **partielle** : les sous-agents et le patch multi-fichiers ne sont pas disponibles.
- Le bouton **+** du composeur ouvre le sélecteur de fichiers Android. Jusqu’à quatre pièces jointes : **50 Mo par PDF**, **20 Mo par image ou autre fichier**, et **64 Mo au total par message** (Mo = 1 048 576 octets). Les fichiers sont transmis à DeepSeek et affichés dans la bulle avec une vignette quand possible. Une copie reste dans le stockage privé pour l’aperçu jusqu’à suppression de la discussion. La prise en charge réelle dépend de DeepSeek, de la mémoire et du réseau de l’appareil ; le suivi de téléversement est heuristique et ne prouve pas toujours la réception.
- La mémoire est activée par défaut et contrôlable dans les paramètres. Sa création automatique dépend du modèle. Prompt système, préférences et mémoire sont transmis au début d’une nouvelle discussion ; ils ne sont pas réinjectés automatiquement lors d’une reprise par URL.
- Les outils Work/Code n’exigent pas de confirmation individuelle et n’ont pas de plafond fixe de tours. Le bouton d’arrêt ne garantit pas l’interruption d’une requête déjà soumise ou d’une commande SSH déjà lancée.

### Connecteurs Work et Code

- Dans **Work**, le bouton **+** permet d’activer Google Workspace pour la discussion courante. L’autorisation se fait par le SDK Google natif depuis l’application (interface Google/Play Services), **jamais dans la WebView DeepSeek**. L’agent reçoit des outils Drive (recherche/lecture), création de Google Docs et création/remplissage de Sheets. En cas d’activation après le début d’une discussion, la liste des outils est annoncée dans le prochain message ; désactiver le connecteur retire son accès à tous les fils.
- Dans **Code**, le même bouton permet d’activer GitHub. L’utilisateur saisit le nom du compte et un PAT ; préférer un **fine-grained token** restreint aux dépôts concernés et aux permissions minimales (Contents/Metadata, Issues si nécessaire). Le secret est chiffré par Android Keystore et n’est ni journalisé ni ajouté au prompt. Un test vérifie que le compte retourné par GitHub correspond au nom saisi. Les outils couvrent dépôts, lecture de fichiers, issues et écriture d’un fichier avec commit explicite.
- **Paramètres → Connecteurs** permet de modifier, tester ou déconnecter les comptes. L’interface n’affiche plus « connecté » avant un test d’accès réussi. La déconnexion désactive le connecteur dans chaque discussion. Chaque accès ne s’applique qu’au mode et aux fils où il est activé ; les outils et leurs instructions ne sont transmis au modèle que dans ces fils.
- Google exige un projet Google Cloud configuré par le propriétaire : activer Drive API, Docs API et Sheets API, puis enregistrer un client OAuth Android pour `com.nemesis.apk` avec l’empreinte SHA-1 de la clé de signature correspondante et ajouter les comptes de test sur l’écran de consentement. La connexion en direct n’est pas validée dans cette préversion : aucun compte OAuth n’était lié sur l’émulateur. Les API de connexion Google utilisées ici sont dépréciées par Google : prévoir leur migration vers l’API d’autorisation recommandée avant une diffusion durable.
- Cette première version n’est pas encore une intégration complète de tous les produits Workspace/GitHub. Elle ne modifie que les ressources annoncées par les outils et les permissions réellement accordées. Les retours live, la révocation Google sur le compte et la séparation par compte restent à tester après configuration OAuth.

## Sessions DeepSeek et fiabilité

En beta.9, le redémarrage du moteur DOM est manuel et ne renvoie pas le message courant. Les tests automatisés vérifient délais/observations lentes et rétablissement du compteur de santé; ils ne démontrent pas une stabilité absolue ni le comportement du site DeepSeek réel. Vérifiez la discussion après un délai prolongé avant de reprendre.
Chaque discussion peut reprendre son URL DeepSeek sans renvoyer tout son historique. La beta.4 attend que le composeur soit utilisable, même si la bonne URL est déjà ouverte, et vérifie l’URL au moment de l’envoi. Elle ne rejoue pas automatiquement un message dont la soumission est incertaine ; si nécessaire, vérifiez la conversation et utilisez la reprise explicite. En beta.5, « Vérifier la page DeepSeek actuelle » et le navigateur NemApi ouvrent la WebView existante sans revenir automatiquement à l’accueil : une vérification en cours a ainsi une chance de rester visible. Un vrai refus de sécurité DeepSeek peut néanmoins persister, y compris si aucun challenge n’est affiché ; attendre et vérifier avant toute reprise.

Le champ a été trouvé sur la page connectée stabilisée et une course de préparation a été corrigée. Cela ne prouve pas que tous les cas `input_missing` observés sur d’autres appareils sont résolus. Une modification du site, le réseau ou un challenge peuvent empêcher l’envoi. La beta.7 corrige une course autour du bouton « Continue » et reprend seulement les lectures DOM après un délai temporaire ; elle ne rejoue pas automatiquement les envois ou clics. Si un flux échoue, le texte déjà reçu est conservé comme réponse partielle, sans exécuter les actions qu’il pourrait contenir. La correction a été testée avec un fournisseur simulé local, **pas sur DeepSeek réel après le correctif** : ne considérez pas une réponse ou un script incomplet comme terminé.

NemApi communique avec l’agent via une passerelle HTTP interne sur loopback, port aléatoire et secret en mémoire. Cela réduit l’exposition entre applications mais ne protège pas contre un appareil compromis. Le service de premier plan affiche une notification sur Android 8.1. Android peut interrompre ou différer les tâches malgré les mécanismes de surveillance ; la disponibilité permanente et la reprise après mort du processus ne sont pas garanties.

## Fichiers, SSH, skills et MCP

Avec l’autorisation de stockage, `Stockage interne/Nemesis` contient `Skills`, `Work` et `Code` ; les fichiers sont sous `Work/<nom-de-discussion>` ou `Code/<nom-de-discussion>`. Les noms en double sont distingués et les anciens dossiers UUID sont migrés sans écraser les fichiers. Un journal permet de finir une migration interrompue ; les anciens dossiers UUID directement vides peuvent être retirés, mais les dossiers et fichiers utilisateur ne sont pas nettoyés aveuglément. Sans permission, l’ancien espace privé commun sert encore de repli et ne bénéficie pas de cette organisation par discussion. Le dossier partagé peut être accessible aux autres applications autorisées et survivre à la désinstallation. Sauvegardez les fichiers avant mise à jour.

Les outils `read_file`, `write_file`, `edit`, `append`, `list_dir`, `glob` et `grep` utilisent les fichiers locaux Android. L’outil `bash` exécute les commandes sur le serveur SSH configuré; il ne voit pas automatiquement ces fichiers locaux. `download_file` rapatrie un chemin distant précis. `read_file` lit le texte, extrait simplement le texte de DOCX/PPTX/XLSX et transmet JPEG/PNG/WebP et PDF au fournisseur pour analyse ; la mise en forme et les images des documents Office peuvent manquer. Audio et vidéo ne sont pas analysés par `read_file`. La compréhension des pièces jointes dépend du fournisseur.

La recherche utilise Wikipédia en français et Bing, avec DuckDuckGo en repli si nécessaire ; les requêtes sont transmises à ces services. Les sources et résultats peuvent être incomplets ou bloqués, et aucun contrôle anti-robot n’est contourné.

SSH est distinct du stockage Android. La clé du serveur est mémorisée à la première connexion (TOFU), puis une modification est refusée. La première connexion n’est pas protégée contre une usurpation. Les commandes s’exécutent avec les droits du compte configuré ; chaque commande démarre un nouveau shell. Le transfert SFTP vers la discussion vérifie le SHA-256. Aperçu intégré pour PDF et images ; DOCX en texte sans mise en forme. Utilisez un environnement de confiance et des sauvegardes.
Le champ SSH accepte un hôte/IP, `hôte:port`, `utilisateur@hôte` ou `ssh://utilisateur@hôte:port`; un bouton de test ouvre une session sans exécuter de commande. Un serveur hors du Wi-Fi local est utilisable s’il est joignable par DNS public, VPN ou port SSH accessible. L’URL Web d’un GitHub Codespace n’est pas un endpoint SSH : le tunnel/agent GitHub et l’authentification par clé ne sont pas encore intégrés, donc ne renseigne pas simplement l’URL `app.github.dev`.

Dix-huit skills tiers sont embarqués puis copiés sans écrasement vers `Nemesis/Skills`, après autorisation. Ils sont listables, lisibles et installables depuis GitHub ; leurs scripts et dépendances ne sont pas tous fournis. Consultez [SKILLS-SOURCES.md](SKILLS-SOURCES.md) et les licences tierces. Les contenus de skills et les résultats MCP sont non fiables, en particulier avant exécution.

MCP prend en charge Streamable HTTP (JSON ou SSE en réponse) et stdio via SSH pour `initialize`, `tools/list` et `tools/call` ; le jeton Bearer HTTP est protégé par Android Keystore. OAuth interactif, ancien HTTP+SSE, ressources/prompts et requêtes serveur avancées ne sont pas pris en charge. HTTP sans TLS expose les données.

## Architecture et développement

- `app/src/main/java/com/nemesis/apk/` : interface native, tâches, service, SSH et ponts MCP.
- `app/src/main/python/` : boucle d’agent, fournisseur NemApi, mémoire, outils Web, skills et MCP ; Python 3.11 embarqué via Chaquopy.
- `nemapi-embedded/` et `nemapi/` : intégration Android et copie de NemApi ; adaptateur DeepSeek dans `nemapi/app/src/main/assets/js/adapter-deepseek.js`.
- `tests/` : tests Python et JavaScript ; `app/src/androidTest/` : tests Android instrumentés.
- [CONTEXTE.md](CONTEXTE.md) : état et reprise ; [AGENTS.md](AGENTS.md) : consignes de travail.

### Compilation et tests

JDK 17 ou 21, Android SDK 34 et Python 3.11. L’environnement utilisé pour beta.5 disposait de Java 21 et du SDK sous `/workspaces/android-sdk`.

```bash
export ANDROID_HOME=/workspaces/android-sdk
export JAVA_HOME=/usr/local/sdkman/candidates/java/21.0.13.0+2-ms
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
./gradlew --no-daemon --max-workers=1 :app:assembleDebug :app:assembleDebugAndroidTest
PYTHONPATH=app/src/main/python python3 -m unittest discover -s tests -v
node tests/test_provider_readiness.js
node tests/test_adapter_dom.js
node tests/test_beta5_attach.js
node nemapi/tools/test-hook.js
node nemapi/tools/test-continue.js
(cd nemapi && ./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest)
```

Ces chemins Java et SDK sont propres au Codespace documenté : adaptez-les si nécessaire. L’APK est générée dans `app/build/outputs/apk/debug/`. Installer avec `adb install -r` ; **ne pas désinstaller ni effacer les données** pour conserver la session. Les sauvegardes de cookies, clés et sessions sont sensibles et doivent rester hors Git.

Validation beta.8 : 58 tests Python, tests JavaScript, neuf tests unitaires NemApi et 17 tests Android hors réseau sur émulateur Android 8.1 x86. Les essais incluent une lecture lente de la progression, un chargement de discussion ralenti, la navigation concurrente, une réponse simulée de plus de 100 000 caractères, deux continuations locales successives et un transfert local de 50 Mio. Un essai distinct de mise à jour beta.7 → beta.8 a préservé une discussion, son URL, son état et un fichier privé sur émulateur x86. L’APK regroupe les bibliothèques ARMv7 et x86 32 bits ; leur présence et leur architecture ELF ont été vérifiées, mais cela ne démontre pas leur fonctionnement sur chaque appareil.

La beta.6 a produit une réponse réelle longue avec trois continuations dans un essai ponctuel avant la correction ; ce succès n’établit pas la fiabilité des versions suivantes. Les deux comptes disponibles sont signalés bloqués et **aucune validation DeepSeek réelle des correctifs beta.7/8 n’a été possible**. Aucun test sur appareil ARM physique. Une image officielle ARM Android 8.1 a pu être démarrée, mais n’accepte que des applications ARM 64 bits, pas l’APK ARM 32 bits ; aucun test ARM 32 bits n’a donc pu être réalisé. Le test 50 Mio est local et synthétique, pas un téléversement réel de cette taille. Une petite image et un petit PDF avaient été analysés lors d’un test réel beta.5. Ne pas relancer de requête ou navigation DeepSeek sans accord de l’utilisateur.

## Licences et crédits

Le provider et le parseur adaptés de NEMESIS-AGENT proviennent du commit `55e8daffb27c6da23274b86cf63d1524b312edb7`, licence MIT : voir [LICENSE-NEMESIS](LICENSE-NEMESIS). La copie NemApi conserve sa licence dans `nemapi/LICENSE-UPSTREAM`. Pour les skills tiers, consulter [SKILLS-SOURCES.md](SKILLS-SOURCES.md) et `third_party/`. Les conditions d’utilisation des services externes restent applicables.
