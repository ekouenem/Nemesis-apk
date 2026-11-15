# NEMESIS Android v0.1.0-beta.13

Préversion sideload, `versionCode 16`, compatible Android 8 / API 26 et supérieur. NemApi intégré reste le moteur principal. Cette release propose trois APKs : Intel (`x86` + `x86_64`), ARM 32 bits (`armeabi-v7a`) et ARM64 (`arm64-v8a`). Installer le binaire correspondant par-dessus l’application présente ; ne pas désinstaller ni effacer les données. Signature debug, pas production.

## Correctifs NemApi repris du patch utilisateur

- Erreurs `security_challenge`, `provider_cooldown`, `login_required`, `user_intervention_required`, `provider_not_configured` et `cancelled` rendues non rejouables automatiquement par `nemapi_v3.py`; backoff croissant pour les autres erreurs HTTP.
- Pacing : minimum 8 s entre les envois ; refroidissement 3 min après un challenge, 15 min à partir du troisième refus.
- Erreurs HTTP explicites (challenge/cooldown 429, session expirée 401), arrêt sur session refusée, état et notification de challenge.
- Restauration de cookies comparée par nom, jamais par longueur ; les cookies actuels ne sont pas écrasés, la copie de secours expire après 12 h et est supprimée à la détection d’une session refusée.
- Délai de réaction au challenge porté à 45 s.
- WebView : version Chrome lue depuis le provider installé, modes d’identité desktop/android, injection du hook limitée aux domaines DeepSeek, diagnostics fingerprint et PoW.
- En-têtes réseau provider observés une fois en lecture seule ; aucun proxy SSE ni suppression de `X-Requested-With` n’est implémenté.

Ce patch vise à réduire les renvois automatiques et les déconnexions ; il **ne garantit pas l’absence de blocage** par le provider. Le comportement sur compte réel et sur 7 jours n’a pas été validé.

## Correctif Alpine

Le runtime copie le bundle `certifi` de l’application dans le magasin CA du rootfs avant la première mise à jour de paquets ; TLS reste activé. Le marqueur toolchain v3 permet une reconfiguration d’un rootfs existant sans le supprimer. L’installation Alpine dans un environnement ARM n’a pas été reproduite ici faute d’espace pour installer une image système ARM : ce correctif reste à confirmer sur téléphone.

## Tests et builds

- 49 tests Python ciblés réussis ; `py_compile` réussi.
- `nemapi/tools/test-hook.js` : tous les tests passent.
- La compilation release x86 incluant le nouveau Kotlin NemApi a réussi ; compilation finale des trois variantes et vérification ABI/signature jointes à la release.
- Pas de validation OAuth/DeepSeek sur compte réel, pas d’essai sur appareil ARM physique, pas de test d’authentification Alpine sudo, pas de correction de Debian.

## Limites conservées

- Debian PRoot/APT échoue encore avec `201::URIDone (14: Bad address)` et des avertissements `chown`/`chmod`. Voir `docs/DEBUG-DEBIAN-PROOT-APT.md`; Debian et sudo ne sont pas annoncés fonctionnels.
- Alpine ARM/TLS reste à valider ; vérifier l’heure automatique et le réseau si l’erreur TLS persiste. Ne pas désactiver la validation des certificats.
- Préversion expérimentale, signature debug, targetSdk 28, distribution sideload uniquement.


Rapport source et patch d’origine : [`RAPPORT-DeepSeek-blocage-comptes.md`](RAPPORT-DeepSeek-blocage-comptes.md) et le fichier patch exact joint aux assets de la release GitHub beta.13. Le rapport comporte un addendum sur les validations réellement effectuées pour beta.13.
