# Notes de version — NEMESIS Android

## v0.1.0-beta.14 — correctif de pacing et de flux

- L’intervalle normal de 8 s est attendu localement ; il ne déclenche plus un HTTP 429 ni l’échec du second message.
- Le cooldown après un vrai challenge de sécurité reste explicite et non rejouable.
- Les erreurs pré-soumission sont retournées directement avec leur code au lieu du faux résumé « Échec après N tentatives ».
- Tests de pacing et de propagation SSE ajoutés.


## Correctif — blocage des comptes DeepSeek et déconnexions de session (branche `fix/deepseek-account-blocking`)

- Un refus de vérification de sécurité (PoW) du provider est désormais un **code d'erreur explicite** (`security_challenge`, HTTP 429). Il n'est **plus rejoué automatiquement** : le client Python ne retente plus ces requêtes, et le moteur applique un refroidissement (3 min, puis 15 min après trois refus).
- Arrêt immédiat sur session expirée (`login_required`, HTTP 401) : aucune requête n'est envoyée au provider sans session authentifiée, et la copie de session périmée est abandonnée.
- **Cadence d'envoi** : deux envois ne s'enchaînent pas à moins de 8 s.
- Correction de la **restauration de session** : les cookies n'étaient réinjectés qu'en comparant la longueur des chaînes, ce qui écrasait une session valide par une copie plus courte et déconnectait le compte. La comparaison se fait désormais par nom de cookie, la copie est limitée à 12 h et n'est jamais réinjectée au-dessus d'un cookie vivant.
- **Identité du navigateur** : le User-Agent annonce désormais la version de Chrome réellement installée (au lieu de `Chrome/126` figé) et un mode `android` cohérent avec l'appareil est disponible (`POST /api/identity`). Le hook réseau n'est plus installé que sur l'origine du provider.
- Le hook réseau se rend **indétectable** par une simple vérification `toString` de `fetch` / `XMLHttpRequest` (marquage « native code »).
- Diagnostics en lecture seule : `GET /api/debug/fingerprint` (empreinte navigateur lue dans la page), journalisation unique des en-têtes envoyés au provider, et mesure du délai challenge PoW → complétion. Page de comparaison : `assets/web/fingerprint.html`.
- Un refus de vérification de sécurité déclenche une **notification** dans NEMESIS : l'utilisateur vérifie la session, aucun renvoi automatique n'est déclenché.


## v1.0.0 — première release 1.0

- Nouvelle interface Agent / Code, projets, historique et automatisations locales.
- Environnements de développement avec PRoot et outils terminal embarqués, en complément de SSH distant.
- Outils Android natifs et actions d’accessibilité activées explicitement par l’utilisateur.
- Option TokenHarbor avec clé éphémère en mémoire, test du catalogue et retour à DeepSeek.
- Améliorations des workspaces, de l’aperçu Web intégré, des pièces jointes et des tests d’intégration Android.
- Le connecteur GitHub de la bêta n’est pas inclus; les anciens identifiants GitHub correspondants sont effacés sans effacer volontairement les autres données NEMESIS.

### Vérification et limites

- `versionCode 11`, `versionName 1.0.0`; Android 8.0+ et ABIs ARMv7, ARM64, x86 et x86_64.
- APK debug, tests Android et APK release compilés; l’APK release garde l’identité de développement des bêtas pour permettre la mise à jour. **Ce n’est pas une clé de production.**
- 73 tests Python passent; tests DOM/flux/pièces jointes JavaScript, tests unitaires NemApi et tests synthétiques TokenHarbor réussis.
- Aucun appel réel de chat TokenHarbor n’est revendiqué.
- Les tests instrumentés ne sont pas exécutés sur l’émulateur contenant les données à préserver. PRoot est inclus mais son installation/exécution complète n’est pas validée; un smoke test Alpine précédent s’est arrêté sur un refus de permission pendant `apk add`. Aucun essai réel Google OAuth, ni appareil physique, n’est revendiqué.
- `targetSdk 28` maintenu pour le sideload et le stockage historique; pas de distribution Google Play.
