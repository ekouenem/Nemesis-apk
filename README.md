# NEMESIS Android

NEMESIS est une application Android native qui réunit, dans une seule APK, une interface de conversation et une adaptation du moteur d’agent Python de NEMESIS-AGENT, reliées à une copie intégrée de NemApi-Apk. Le seul fournisseur intégré actuellement est **DeepSeek** : l’utilisateur doit s’y connecter dans l’application. L’API locale ne rend pas le modèle autonome ni utilisable hors ligne.

> **Préversion : v0.1.0-beta.8.** Elle est utilisable pour les essais, mais les limites ci-dessous restent importantes. L’architecture réelle est native Kotlin + Python/Chaquopy + NemApi/WebView, et non l’architecture Kotlin/Koog décrite dans un ancien plan provisoire.

## Installation

- [Télécharger l’APK beta.8](https://github.com/ekouenem/Nemesis-apk/releases/download/v0.1.0-beta.8/NEMESIS-v0.1.0-beta.8.apk) · [Notes de version](https://github.com/ekouenem/Nemesis-apk/releases/tag/v0.1.0-beta.8)
- Android minimum déclaré : **8.0 / API 26** ; cible de terrain : Android 8.1. Architectures incluses : `armeabi-v7a` et `x86`, 32 bits. Les essais Android ont été faits sur émulateur **x86 Android 8.1**, pas sur appareil ARM ; la présence d’une ABI n’est pas une validation matérielle.
- APK **debug**, pas une signature de production. La beta.8 est signée avec la même clé de développement que la beta.7 : l’installation par-dessus devrait préserver les données. Sauvegardez-les au préalable. Si la clé de signature est perdue, une future mise à jour pourra nécessiter une réinstallation et entraîner la perte des données privées.
- SHA-256 de l’APK publiée : `07aab456d2cd8cf1820c0ffad358ed0b68242f0f41167575833065be594e6a6f`.

Ouvrez l’application et configurez DeepSeek dans son interface intégrée. La session est conservée dans le stockage privé de la WebView quand Android le permet, mais une reconnexion ou un CAPTCHA peuvent être demandés. La connexion Google automatique du compte Android n’est pas intégrée : OAuth Google interdit l’utilisation d’une WebView embarquée et le navigateur système ne partage pas automatiquement ses cookies avec celle-ci.

La notification « NEMESIS actif » propose **Fermer** : l’écoute du serveur NemApi et sa relance planifiée sont arrêtées. Ouvrir de nouveau NEMESIS les réactive. Une requête ou commande déjà lancée peut ne pas s’interrompre immédiatement. La WebView et ses données de session ne sont pas effacées. Android peut limiter les notifications et services en arrière-plan.

Pour utiliser Termux sur le même appareil, consultez le [script public d’installation SSH](https://github.com/nemesishighschool-cloud/nemesis-termux-ssh). Le serveur écoute localement par défaut ; le nom choisi est un alias du compte unique Termux, pas un compte isolé. La commande Bash et le transfert SFTP NEMESIS → Termux ont été testés sur émulateur Android 8.1 x86.

## Utilisation

- **Chat** : conversations, streaming, Markdown, mémoire, copie et appréciation locale des réponses ; la durée est affichée lorsqu’elle est disponible. Le bouton **↓** ramène en bas si du contenu se trouve plus bas. Le streaming ne force pas le défilement pendant la lecture. Pour limiter les blocages, l’historique se charge progressivement (bouton « Afficher les messages précédents »). Les messages de plus de 8 000 unités UTF-16 s’affichent par parties en texte brut ; la copie conserve le texte complet. Pendant un long flux, seul un aperçu de la fin est affiché, sans supprimer le contenu reçu.
- **Work** : recherche et lecture Web, fichiers, SSH, skills et MCP, avec cartes d’outils discrètes et durée totale de chaque tâche terminée. Le prompt vise des explications adaptées au profil.
- **Code** : outils Work et outils de programmation supplémentaires, avec cartes dépliables et durée totale de chaque tâche terminée. L’intégration du moteur d’origine est **partielle** : notamment, les sous-agents et le patch multi-fichiers ne sont pas disponibles.
- Le bouton **+** du composeur ouvre le sélecteur de fichiers Android. Jusqu’à quatre pièces jointes : **50 Mo par PDF**, **20 Mo par image ou autre fichier**, et **64 Mo au total par message** (Mo = 1 048 576 octets). Les fichiers sont transmis à DeepSeek et affichés dans la bulle avec une vignette quand possible. Une copie reste dans le stockage privé pour l’aperçu jusqu’à suppression de la discussion. La prise en charge réelle dépend de DeepSeek, de la mémoire et du réseau de l’appareil ; le suivi de téléversement est heuristique et ne prouve pas toujours la réception.
- La mémoire est activée par défaut et contrôlable dans les paramètres. Sa création automatique dépend du modèle. Prompt système, préférences et mémoire sont transmis au début d’une nouvelle discussion ; ils ne sont pas réinjectés automatiquement lors d’une reprise par URL.
- Les outils Work/Code n’exigent pas de confirmation individuelle et n’ont pas de plafond fixe de tours. Le bouton d’arrêt ne garantit pas l’interruption d’une requête déjà soumise ou d’une commande SSH déjà lancée.

## Sessions DeepSeek et fiabilité

Chaque discussion peut reprendre son URL DeepSeek sans renvoyer tout son historique. La beta.4 attend que le composeur soit utilisable, même si la bonne URL est déjà ouverte, et vérifie l’URL au moment de l’envoi. Elle ne rejoue pas automatiquement un message dont la soumission est incertaine ; si nécessaire, vérifiez la conversation et utilisez la reprise explicite. En beta.5, « Vérifier la page DeepSeek actuelle » et le navigateur NemApi ouvrent la WebView existante sans revenir automatiquement à l’accueil : une vérification en cours a ainsi une chance de rester visible. Un vrai refus de sécurité DeepSeek peut néanmoins persister, y compris si aucun challenge n’est affiché ; attendre et vérifier avant toute reprise.

Le champ a été trouvé sur la page connectée stabilisée et une course de préparation a été corrigée. Cela ne prouve pas que tous les cas `input_missing` observés sur d’autres appareils sont résolus. Une modification du site, le réseau ou un challenge peuvent empêcher l’envoi. La beta.7 corrige une course autour du bouton « Continue » et reprend seulement les lectures DOM après un délai temporaire ; elle ne rejoue pas automatiquement les envois ou clics. Si un flux échoue, le texte déjà reçu est conservé comme réponse partielle, sans exécuter les actions qu’il pourrait contenir. La correction a été testée avec un fournisseur simulé local, **pas sur DeepSeek réel après le correctif** : ne considérez pas une réponse ou un script incomplet comme terminé.

NemApi communique avec l’agent via une passerelle HTTP interne sur loopback, port aléatoire et secret en mémoire. Cela réduit l’exposition entre applications mais ne protège pas contre un appareil compromis. Le service de premier plan affiche une notification sur Android 8.1. Android peut interrompre ou différer les tâches malgré les mécanismes de surveillance ; la disponibilité permanente et la reprise après mort du processus ne sont pas garanties.

## Fichiers, SSH, skills et MCP

Avec l’autorisation de stockage, `Stockage interne/Nemesis` contient `Skills`, `Work` et `Code` ; les fichiers sont sous `Work/<nom-de-discussion>` ou `Code/<nom-de-discussion>`. Les noms en double sont distingués et les anciens dossiers UUID sont migrés sans écraser les fichiers. Un journal permet de finir une migration interrompue ; les anciens dossiers UUID directement vides peuvent être retirés, mais les dossiers et fichiers utilisateur ne sont pas nettoyés aveuglément. Sans permission, l’ancien espace privé commun sert encore de repli et ne bénéficie pas de cette organisation par discussion. Le dossier partagé peut être accessible aux autres applications autorisées et survivre à la désinstallation. Sauvegardez les fichiers avant mise à jour.

Les outils `read_file`, `write_file`, `edit`, `append`, `list_dir`, `glob` et `grep` utilisent les fichiers locaux sans SSH en Work et Code. `read_file` lit le texte, extrait simplement le texte de DOCX/PPTX/XLSX et transmet JPEG/PNG/WebP et PDF au fournisseur pour analyse ; la mise en forme et les images des documents Office peuvent manquer. Audio et vidéo ne sont pas analysés par `read_file`. La compréhension des pièces jointes dépend du fournisseur.

La recherche utilise Wikipédia en français et Bing, avec DuckDuckGo en repli si nécessaire ; les requêtes sont transmises à ces services. Les sources et résultats peuvent être incomplets ou bloqués, et aucun contrôle anti-robot n’est contourné.

SSH est distinct du stockage Android. La clé du serveur est mémorisée à la première connexion (TOFU), puis une modification est refusée. La première connexion n’est pas protégée contre une usurpation. Les commandes s’exécutent avec les droits du compte configuré ; chaque commande démarre un nouveau shell. Le transfert SFTP vers la discussion vérifie le SHA-256. Aperçu intégré pour PDF et images ; DOCX en texte sans mise en forme. Utilisez un environnement de confiance et des sauvegardes.

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
