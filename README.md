# NEMESIS Android — développement

Application Android native utilisant Python 3.11 (Chaquopy), avec le provider et le parseur issus de NEMESIS-AGENT (commit `55e8daffb27c6da23274b86cf63d1524b312edb7`, licence MIT : voir `LICENSE-NEMESIS`). **Le moteur complet n’est pas encore intégré** : les modes et outils sont adaptés progressivement pour Android.

## État actuel

- Interface native claire avec conversations, Markdown, streaming, cartes d’outils dépliables, pièces jointes image et texte.
- Chat : discussion sans outils. Work : `web_search`, `web_fetch`, lecture, écriture, édition et commande SSH ; les écritures et commandes demandent une confirmation. Code : ces outils plus certains outils mobiles ; il ne comprend pas encore tous les outils, sous-agents et intégrations du moteur d’origine.
- Les fichiers locaux de l’application et ceux de l’environnement SSH sont distincts ; aucune synchronisation automatique n’est fournie. Chaque commande SSH démarre un nouveau shell. La clé du serveur doit être vérifiée par son empreinte SHA256.
- Personnalisation et mémoire explicite enregistrées localement. Les rubriques skills et MCP sont présentes mais indiquées indisponibles : leur fonctionnement Android reste à développer.
- La recherche DuckDuckGo peut renvoyer une vérification anti-robot, désormais signalée comme erreur ; aucun contournement n’est fourni. `web_fetch` accepte uniquement les URL HTTPS publiques, sans redirection.
- Pour une conversation existante, les changements de prompt sont transmis dans le prochain message sous forme de texte préfixé, pas comme un nouveau rôle système prioritaire. Leur respect dépend du modèle et ne constitue pas une garantie de sécurité.
- Images JPEG, PNG, WebP (8 Mo max. par fichier) et fichiers texte UTF-8 (120 Ko max. par fichier), jusqu’à quatre pièces jointes. Les connecteurs et autres formats ne sont pas encore disponibles.
- NemApi est une application compagnon séparée. La copie spécifique dans `nemapi/` écoute sur `127.0.0.1:8090` et gère une URL DeepSeek par session, sans renvoyer tout l’historique. En cas d’état incertain après une requête, la session est bloquée plutôt que mélangée.
- **Sécurité à renforcer** : loopback ne protège pas contre les autres applications du téléphone. L’API locale n’est pas encore authentifiée de manière fiable entre les deux applications ; ce prototype n’est pas destiné à la production.

## Build et tests

JDK 17/21, Android SDK 34 : `./gradlew :app:assembleDebug`. Construire la variante compagnon depuis `nemapi/` avec `./gradlew :app:testDebugUnitTest :app:assembleDebug`. Installer les mises à jour avec `adb install -r` pour conserver les données existantes ; ne pas désinstaller NemApi ni effacer les données de sa WebView. Python : `PYTHONPATH=app/src/main/python python -m unittest discover -s tests -v`.

Android 8.1 x86 a été testé dans l’émulateur. L’APK inclut `armeabi-v7a`, mais cette ABI n’a pas encore été testée sur un appareil représentatif. Les sessions et identifiants ne doivent jamais entrer dans Git. La licence de la copie NemApi doit être clarifiée avant redistribution.
