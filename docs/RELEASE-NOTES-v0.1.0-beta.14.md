# NEMESIS Android v0.1.0-beta.14 — correctif de flux et de cadence

Préversion sideload, `versionCode 17`, Android 8 / API 26 minimum. Trois APK : Intel x86/x86_64, ARM32 `armeabi-v7a`, ARM64 `arm64-v8a`. Choisir le bon binaire dans la release GitHub. Installer par-dessus la version existante, sans désinstaller ni effacer les données. Signature debug conservée, pas une clé de production.

## Correctif de stabilité

La beta.13 appliquait le même code d’erreur `provider_cooldown` au cooldown de sécurité et à l’espacement normal minimal de 8 secondes. Un message envoyé peu après la réponse précédente pouvait donc être refusé localement, puis apparaître dans l’application comme `Flux interrompu` et `Échec après 1 tentatives`.

Désormais, NemApi attend le délai de cadence normal (avec keep-alive et prise en compte de l’annulation) avant d’écrire/soumettre le prompt. Seul le cooldown réellement posé après un challenge de sécurité reste non rejouable et visible comme tel. Les erreurs SSE explicitement marquées `not_submitted` sont propagées avec leur code et sans faux résumé de tentatives ; la session n’est pas marquée incertaine.

## Correctifs précédents conservés

Le patch NemApi bêta.13 reste inclus : pas de renvoi automatique après challenge/session expirée, temporisation croissante pour les autres erreurs, restauration de cookies par nom, attente du challenge à 45 s, notifications/diagnostics et modes d’identité du WebView. Le bundle CA certifi est toujours copié dans le rootfs avant la première mise à jour Alpine, sans désactiver TLS.

## Vérifications

- 50 tests Python ciblés réussis, dont test de non-rejeu d’un cooldown pré-soumission.
- Tests JS `nemapi/tools/test-hook.js` réussis.
- Tests Kotlin JUnit `PacingTest` exécutés et réussis ; compileRelease Kotlin réussie.
- Trois builds release reconstruits pour Intel x86/x86_64, ARM32 et ARM64 ; manifestes, ABI et signatures vérifiés.
- Pas de test DeepSeek réel/7 jours. Pas d’émulateur ARM disponible dans le Codespace ; Alpine et le second message doivent encore être confirmés sur le téléphone de l’utilisateur.

Le cooldown de sécurité reste volontairement non rejouable pour protéger le compte ; le correctif ne garantit pas les décisions d’accès de DeepSeek. Debian PRoot/APT reste en échec connu (`201::URIDone (14: Bad address`) ; sudo Debian n’est pas annoncé fonctionnel.
