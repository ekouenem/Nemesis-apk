# Diagnostic beta.14 — second message refusé à tort par le pacing

## Symptôme observé

Après un premier message réussi, le suivant échouait avec `Flux interrompu : DeepSeek demande une pause après une vérification de sécurité : nouveau renvoi dans 1 s`, suivi de `Échec après 1 tentatives`.

## Cause identifiée dans beta.13

`Pacing.waitNeededMs()` retournait la durée maximale entre deux causes distinctes : (1) cooldown posé après un vrai refus de challenge ; (2) intervalle normal de 8 secondes depuis le dernier envoi. `DeepSeek.run()` traitait les deux comme `NemApiException.cooldown`, renvoyé en HTTP 429 même si aucun challenge de sécurité n’avait été constaté. Dans la voie SSE, `SessionProvider` enveloppait ensuite une erreur explicitement pré-soumission dans une exception générique ; `NemapiV3Provider.send_message()` la résumait en `Échec après 1 tentatives`.

## Changements

- Pacing sépare `minimumIntervalWaitMs()` du cooldown de sécurité.
- `DeepSeek` attend l’intervalle normal avant de remplir le composeur et d’envoyer ; attente en tranches courtes, keep-alive conservé et annulation respectée.
- Un vrai cooldown challenge reste distinct, non rejouable, avec un message explicite ; il n’est jamais transformé en pause de cadence.
- Les erreurs SSE `not_submitted=true` sont propagées avec `error_code` et `retryable=false`, sans préfixe de flux interrompu ni résumé de tentatives ; état incertain remis à faux.
- Tests ajoutés pour l’intervalle minimal et le retour d’un cooldown pré-soumission sans relance.

## Vérifications / limites

50 tests Python et tests Kotlin de `PacingTest` réussis ; tests JS du hook réussis ; compilation release pour les trois groupes d’ABI. Le défaut est reproduit par le chemin d’erreur et des tests déterministes ; aucun téléphone/émulateur ARM n’est disponible dans ce Codespace, donc la vérification finale du second message sur l’appareil utilisateur reste à faire.
