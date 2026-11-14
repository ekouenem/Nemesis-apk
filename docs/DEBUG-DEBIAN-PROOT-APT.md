# Diagnostic à reprendre : installation Debian sous PRoot

## État observé (émulateur `emulator-5554`)

La résolution DNS Android de `deb.debian.org` et `security.debian.org` a été ajoutée au `etc/hosts` du rootfs Debian avant APT. La tentative suivante a dépassé l'étape de résolution et d'extraction, puis a échoué pendant `apt-get install` avec le code 100. L'erreur visible dans l'application contient notamment :

```text
...node-retry_0.13.1.+~cs2.19.16-1_all.deb failed - 201::URIDone (14: Bad address)
W: chown to root:root of file ... failed - 201::URIDone (14: Bad address)
W: chmod 0644 of file ... failed - 201::URIDone (14: Bad address)
```

Le premier essai pointait vers `/var/cache/apt/archives/partial`. Un essai ultérieur a configuré explicitement le cache et les listes sous `/tmp/nemesis-apt` ; le même `Bad address` s'est reproduit, cette fois avec le chemin `/tmp/nemesis-apt/archives/partial`. Donc le déplacement du cache ne corrige pas la panne. Le nouvel APK debug a passé le test unitaire ciblé et s'est compilé, mais l'installation Debian sur l'émulateur **n'a pas réussi**. L'étape DNS semble franchie ; cela ne démontre pas que toutes les requêtes réseau sont saines.

## Ce qui n'est pas encore établi

- `14: Bad address` est un `EFAULT`, mais la preuve actuelle ne permet pas de distinguer une incompatibilité PRoot/tracée avec les syscalls de fichiers (notamment `chown`/`chmod`), un problème du processus d'acquisition APT ou une autre cause.
- Ne pas conclure à un défaut DNS sur la seule base du premier échec, ni à un défaut des permissions Android/SELinux sans reproduction ciblée.
- Le compte Debian `nemesis`, sudo et son mot de passe ne sont pas confirmés : l'installation échoue avant la vérification post-installation.

## Reprise recommandée

1. Garder le rootfs incomplet et le diagnostic d'installation accessibles jusqu'à ce que les logs soient capturés ; éviter de supprimer Alpine ou les données de l'application.
2. Reproduire dans le même émulateur avec sortie intégrale horodatée, en séparant `apt-get update`, téléchargement d'un unique `.deb` et installation/configuration `dpkg`.
3. Faire un test PRoot minimal et non destructif des opérations `open`, `chmod`, `chown`, `rename` et des syscalls de transfert de mémoire, dans un rootfs jetable. Capturer `PRoot -V`/les options de compilation et comparer le même test à chaque ABI Android.
4. Vérifier explicitement si la version PRoot embarquée a été construite avec les bons appels `process_vm_readv`/`process_vm_writev` et un niveau d'API NDK compatible avec les Android cibles ; c'est une piste à vérifier, pas la cause démontrée.
5. Tester les options APT qui désactivent son sandbox de téléchargement, sans supposer que `APT::Sandbox::User=root` suffit ; comparer l'accès au répertoire `partial` et les appels `chown`/`chmod` sous PRoot.
6. Ajouter un test instrumenté/regression pour la commande fautive, puis refaire l'installation Debian complète en émulateur et vérifier `id`, hostname `nemesis`, `sudo -S id -un` et quelques outils avant de déclarer résolu.

## Périmètre

Le runtime reste un shell PRoot privé à l'application (pas root Android). La publication de beta.10 conserve ce défaut Debian comme limitation connue ; elle ne signifie pas que Debian ou sudo ont été réparés.
