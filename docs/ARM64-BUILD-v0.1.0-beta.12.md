# NEMESIS Android v0.1.0-beta.12 — ARM64 build candidate

APK sideload limitée à `arm64-v8a`, destinée aux appareils Android 64 bits. `versionCode 15` permet une mise à jour par-dessus beta.10/beta.11 sans désinstaller l’application ni effacer ses données. La signature reste la clé debug du projet, identique à la beta.10 ; ce n’est pas une signature de production.

Cette build reprend le rafraîchissement du bundle CA du rootfs avant le premier `apk update` (beta.11). Elle ne désactive pas la validation TLS. L’architecture arm64 permet au système d’utiliser uniquement les bibliothèques arm64 ; elle n’est pas en soi un correctif aux erreurs TLS. Aucun test d’installation d’Alpine dans un appareil ou émulateur ARM64 n’a pu être réalisé dans le Codespace faute d’espace disque pour y installer une image système ARM. Le build et son contenu ABI sont à contrôler ; ne pas annoncer Alpine comme validé avant l’essai sur appareil.

Build :

```bash
./gradlew --no-daemon --offline -PnemesisAbis=arm64-v8a :app:assembleRelease
```

Pour le test sur téléphone : installer l’APK par-dessus l’installation existante, conserver les données, puis relancer l’installation d’Alpine et partager le message TLS complet si l’erreur persiste. Vérifier aussi la date/heure automatique et essayer un réseau différent ; ne pas désactiver la validation des certificats.
