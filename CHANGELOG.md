# Notes de version — NEMESIS Android

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
