# NEMESIS Android — développement

Application Android native utilisant Python 3.11 (Chaquopy), avec le provider et le parseur issus de NEMESIS-AGENT (commit `55e8daffb27c6da23274b86cf63d1524b312edb7`, licence MIT : voir `LICENSE-NEMESIS`). Le moteur complet n’est pas encore intégré ; les outils sont adaptés progressivement pour Android.

## État actuel

- Une seule APK contient NEMESIS et la variante NemApi-Apk. Le moteur DeepSeek démarre avec l’application ; son interface de connexion et ses paramètres s’ouvrent dans NEMESIS. Les données WebView et les cookies restent dans le stockage privé Android. La connexion peut devoir être renouvelée.
- NemApi gère une URL DeepSeek distincte par discussion, sans renvoyer tout l’historique. En cas d’état incertain après une requête, la session est bloquée pour éviter les mélanges.
- La passerelle HTTP intégrée écoute uniquement sur loopback, sur un port aléatoire avec un secret aléatoire en mémoire par processus. Toutes les routes demandent ce secret ; les routes d’administration ne sont pas exposées dans cette configuration. Ce mécanisme réduit l’exposition entre applications mais n’est pas une garantie face à un appareil compromis.
- Interface native claire, barre latérale, Markdown, streaming, cartes d’outils dépliables, pièces jointes image et texte. La recherche DuckDuckGo peut demander une vérification anti-robot, signalée comme erreur ; `web_fetch` accepte les URL HTTPS publiques sans redirection.
- Chat possède uniquement les outils de mémoire. Work ajoute `web_search`, `web_fetch`, lecture, écriture, édition et commande SSH. Code ajoute certains outils mobiles mais ne comprend pas encore tous les outils, sous-agents et intégrations du moteur d’origine. Les rubriques skills et MCP indiquent qu’elles sont indisponibles.
- La mémoire est active par défaut et peut être désactivée. Les souvenirs sont stockés localement, visibles, modifiables et supprimables dans les paramètres ; ils sont transmis au début des nouvelles discussions. L’agent peut créer, modifier et supprimer des souvenirs, mais sa décision de les enregistrer dépend du modèle et n’est pas garantie.
- Les fichiers locaux et les fichiers du serveur SSH sont distincts. Les écritures locales et chaque commande SSH demandent confirmation. Chaque commande SSH démarre un nouveau shell ; la clé du serveur doit être vérifiée par son empreinte SHA256.
- Pour une conversation existante, une mise à jour du prompt et de la mémoire est transmise dans le prochain message comme texte préfixé, pas comme nouveau rôle système prioritaire. Son respect dépend du modèle et ne constitue pas une garantie de sécurité.
- Images JPEG, PNG, WebP (8 Mo maximum par fichier) et fichiers texte UTF-8 (120 Ko maximum), jusqu’à quatre pièces jointes. Les connecteurs et autres formats ne sont pas encore disponibles.

## Build et tests

JDK 17/21, Android SDK 34, Python 3.11 : `./gradlew :app:assembleDebug`. Pour les tests de la copie NemApi-Apk : `cd nemapi && ./gradlew :app:testDebugUnitTest`. Tests Python : `PYTHONPATH=app/src/main/python python -m unittest discover -s tests -v`. Sur une machine de 2 cœurs, `--no-daemon --max-workers=1` limite la consommation mémoire de Gradle.

Installer avec `adb install -r` pour conserver les données : ne pas désinstaller NEMESIS ni effacer son stockage si la session DeepSeek doit être conservée. Une sauvegarde des cookies et du stockage WebView est sensible et ne doit jamais être placée dans Git. Sur un appareil non rooté, les données privées d’une autre application ne peuvent pas être copiées automatiquement.

Un test Android instrumenté facultatif vérifie SSH avec un serveur temporaire et une empreinte incorrecte ; il est ignoré sans paramètres de test et ne remplace jamais une configuration SSH existante. Sur l’émulateur Android 8.1 x86, la connexion et le rejet de la mauvaise empreinte ont réussi avec un serveur Ed25519 temporaire. Les paramètres et le serveur de test ont ensuite été supprimés.

Android 8.1 x86 a été testé dans l’émulateur. L’APK inclut `armeabi-v7a`, mais cette ABI n’a pas encore été testée sur un appareil représentatif. Le moteur automatise l’interface Web DeepSeek ; il peut cesser de fonctionner si celle-ci change et l’utilisation peut être soumise aux conditions du fournisseur. La copie NemApi-Apk conserve sa licence dans `nemapi/LICENSE-UPSTREAM`.
