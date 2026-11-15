# Diagnostic et build candidat ARMv7 — beta.11

## Erreur rapportée

Sur le téléphone `armeabi-v7a`, Alpine échoue pendant `apk update`, avant l'installation des paquets. La cause immédiate affichée est `tls_post_process_server_certificate: certificate verify failed`. Les `WARNING: ... Permission denied` et le code APK 4 sont les conséquences de l'index HTTPS refusé ; ce n'est pas une erreur indiquant que l'architecture ARM n'est pas prise en charge.

Le rootfs Alpine 3.22.8 `armhf` contient bien un bundle CA, identique octet pour octet à celui du rootfs `x86` inspecté (178 791 octets). Le bundle certifi inclus avec l'application est plus récent/différent (235 863 octets dans le build examiné). Cela rend un décalage du magasin CA une piste plausible, mais le message seul ne permet pas d'exclure une date/heure incorrecte sur le téléphone, un proxy TLS ou une interception/réseau.

## Correctif candidat

Avant le premier `apk update` (et également avant APT), le runtime recopie le bundle Mozilla `certifi` déjà embarqué dans l'application vers `/etc/ssl/certs/ca-certificates.crt` dans le rootfs. Le lien Alpine `/etc/ssl/cert.pem` continue d'utiliser ce chemin. La validation TLS reste activée : aucun mode `--no-check-certificate`, dépôt HTTP ou contournement de signature n'est ajouté.

Le marqueur de toolchain passe à v3 afin qu'un rootfs déjà présent soit reconfiguré avec le bundle actualisé au prochain lancement, sans supprimer le rootfs ni les fichiers utilisateur. Le build expose `-PnemesisAbis=armeabi-v7a` pour produire un APK limité à cette ABI, tout en gardant le build universel par défaut.

## Build et vérifications locales

Commande exécutée dans le Codespace :

```bash
JAVA_HOME=/usr/local/sdkman/candidates/java/21.0.13.0+2-ms \
ANDROID_HOME=/workspaces/android-sdk ANDROID_SDK_ROOT=/workspaces/android-sdk \
./gradlew --no-daemon --offline -PnemesisAbis=armeabi-v7a :app:assembleRelease
```

- 49 tests Python ciblés réussis.
- Compilation release ARMv7 réussie.
- APK contrôlée : `versionCode 14`, `versionName 0.1.0-beta.11`, ABI native `armeabi-v7a` seulement, signature identique au certificat debug de beta.10.
- Le build n'a pas été installé sur un appareil ARM réel et le téléchargement/configuration Alpine n'a pas été retesté sur le téléphone de l'utilisateur. Il s'agit donc d'un candidat à valider, pas d'une résolution déclarée.

## Si l'échec TLS persiste

1. Vérifier date, heure et fuseau automatiques sur Android.
2. Ouvrir l'URL Alpine en HTTPS depuis le navigateur du téléphone, puis essayer un autre réseau (Wi-Fi/mobile) pour repérer portail captif ou interception TLS.
3. Garder la vérification TLS activée ; ne pas contourner l'erreur en désactivant la validation des certificats.

Mettre à jour par-dessus l'application beta.10, sans la désinstaller ni effacer ses données. Cette candidate n'a pas été publiée sur GitHub ; elle est fournie séparément pour essai sur le téléphone ARMv7.