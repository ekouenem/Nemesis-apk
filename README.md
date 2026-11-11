# NEMESIS APK — prototype Python

Prototype Android expérimental utilisant le provider `NemapiV3Provider` et `ActionParser` du dépôt NEMESIS-AGENT (commit `55e8daffb27c6da23274b86cf63d1524b312edb7`, licence MIT : voir LICENSE-NEMESIS). Une petite boucle d'adaptation Android les réutilise ; ce n'est pas encore le moteur complet ni l'interface React d'origine.

## Fonctionnement
- Python 3.11 embarqué avec Chaquopy ; ABIs `x86` et `armeabi-v7a`, Android 8.0+.
- NemApi APK **séparé**, démarré sur `127.0.0.1:8090`, avec DeepSeek ouvert et authentifié. DeepSeek utilise Internet.
- Provider Python d'origine : un seul message courant par requête, prompt initial une fois ; le contexte dépend de la conversation dans NemApi/DeepSeek. Pas de garantie d'isolement de plusieurs conversations.
- Seul outil de démonstration `echo` ; pas de shell, SSH, Termux, Work/Code, ni moteur complet. Ne pas utiliser comme agent de production.
- Prototype HTTP en clair vers loopback ; NemApi actuel peut aussi écouter sur le réseau. Ne pas exposer ses ports ni son interface de diagnostic.

## Build
Avec JDK 17 ou 21, Android SDK 34 : `./gradlew :app:assembleDebug`. APK : `app/build/outputs/apk/debug/app-debug.apk`. L'installation nécessite NemApi séparé. La compatibilité ARMv7 doit être testée sur un vrai appareil : un émulateur x86 32 bits n'est pas une preuve ARM.

Tests locaux : `PYTHONPATH=app/src/main/python python -m unittest discover -s tests -v` (dépendance Python requests). L'authentification et les données de session ne doivent jamais être commitées.
