# Diagnostic de l'installation de l'environnement Linux — Debian et ARMv7

Mise à jour après l'analyse du code et la vérification des archives officielles.
Cette note remplace la partie « ce qui n'est pas encore établi » de la note
initiale : plusieurs causes possibles ont été **écartées par vérification**, et
le chemin de correction est désormais instrumenté.

## 1. Causes écartées (vérifiées, pas supposées)

| Hypothèse | Vérification | Résultat |
|---|---|---|
| Archive rootfs Alpine corrompue / empreinte SHA-256 fausse sur ARM | Téléchargement des 4 archives `alpine-minirootfs-3.22.8` officielles et calcul SHA-256 | **Écartée** — les 4 empreintes correspondent exactement à celles épinglées (`armhf` = `1eba5846…`) |
| Archive Debian false ou périmée | Téléchargement des 4 archives `debian-trixie-*-pd-v4.26.0` (proot-distro v4.26.0) | **Écartée** — les 4 empreintes correspondent (`arm` = `868ad59b…`) |
| L'erreur Debian viendrait d'un problème TLS / de certificats | Lecture de `/etc/apt/sources.list` dans l'archive Debian | **Écartée pour Debian** — les dépôts sont en **HTTP** (`deb http://deb.debian.org/debian trixie main`) ; aucun certificat n'est en jeu dans l'erreur `201::URIDone (14: Bad address)`. Cette erreur est **distincte** du problème TLS d'Alpine sur ARMv7 |
| Le déplacement du cache APT corrigerait l'erreur | Observation rapportée (cache sous `/tmp/nemesis-apt`) | **Confirmé que non** — cohérent avec une panne syscall dans le guest, pas avec un chemin de fichier particulier |
| Le magasin CA du rootfs Alpine ARMv7 est en cause | Inspection de l'archive `armhf` | **Très improbable** — le bundle CA d'Alpine ARMhf est identique octet pour octet à celui du rootfs x86 (`178 791` octets) qui, lui, fonctionne. Le bundle de l'application (certifi, plus récent) est déjà injecté par `_seed_guest_ca_bundle` depuis la beta.11 |

## 2. Ce que l'erreur Debian désigne réellement

`201::URIDone (14: Bad address)` est la signature APT d'un **échec de la
méthode de téléchargement** : `errno 14` = `EFAULT`.

Or, dans PRoot 5.1.107, `EFAULT` est précisément le code que renvoie
`write_data()` quand le moteur ne peut plus écrire dans l'espace mémoire du
processus suivi (`src/tracee/mem.c` : le repli `ptrace(POKEDATA)` échoue →
`-EFAULT`). La même valeur est ensuite signalée par `dpkg` pour `chown` et
`chmod`, qui sont des appels système à chemin traduit.

Autrement dit : sur l'appareil de test, **PRoot ne parvient pas à écrire le
chemin traduit dans la mémoire du processus suivi**. Les chemins du guest
(`/var/cache/apt/archives/partial/…`) deviennent, côté hôte, des chemins très
longs (`/data/user/0/<pkg>/files/nemesis-linux/debian-trixie-arm/var/cache/…`) :
c'est cohérent avec le fait que déplacer le cache APT ne change rien.

Point important : **la preuve de Debian vient uniquement de l'émulateur x86_64**
(API 27). Les images système récentes de l'émulateur Android utilisent un
noyau gVisor dont les primitives `ptrace`/`process_vm` sont restreintes : PRoot
y perd son accélérateur d'écriture mémoire. Le même code sur un **appareil
physique** peut se comporter différemment. Il faut donc rejouer le scénario sur
un téléphone avant de conclure que Debian est cassé partout.

## 3. Correctifs apportés (branche `fix/proot-install-armv7-debian`)

### 3.1 Les paquets sont téléchargés par l'HÔTE, installés hors-ligne dans le guest

C'est le correctif de fond pour Debian :

1. le guest fait `apt-get update` (le rootfs Debian n'utilise que HTTP) ;
2. le guest calcule la liste des paquets (`apt-get --print-uris`) ;
3. **l'hôte** télécharge chaque `.deb` avec le Python de l'application et son
   bundle `certifi` — c'est-à-dire la chaîne qui télécharge déjà le rootfs et
   qui fonctionne sur **toutes les ABIs** — et vérifie le SHA-256 indiqué par
   apt ;
4. les paquets sont déposés dans `/var/cache/apt/archives/` du guest ;
5. le guest installe **sans réseau** : `apt-get install --no-download`, avec
   repli automatique sur l'installation en ligne si le cache est incomplet.

Le guest n'a donc plus de requête HTTP/TLS au moment où dpkg/apt écrivent dans
le cache : le chemin réseau — et donc le syscall en échec — sort de
l'équation.

### 3.2 Plus aucune perte de diagnostic

- La sortie de PRoot était tronquée aux **4 000 derniers caractères** : les
  messages du moteur (`Detected broken PTRACE_POKEDATA`, `ptrace(POKEDATA)`,
  `integer under/overflow detected`) sont écrits **au début** de la sortie et
  étaient systématiquement coupés. La sortie complète est désormais conservée,
  écrite dans `files/nemesis-linux/logs/install-<distro>.log`, affichée dans le
  message d'erreur et lisible depuis l'application
  (bouton **Journal d'installation**).
- `PROOT_VERBOSE=1` est forcé dans l'environnement du guest.
- Un **test des primitives PRoot** (`write`, `read`, `chmod`, `chown`, `rename`,
  `symlink`, DNS, HTTPS) est exécuté juste après l'extraction, avant toute
  installation de paquets. Si une primitive fondamentale échoue, l'installation
  s'arrête en nommant la primitive fautive au lieu d'échouer 20 minutes plus
  tard avec un message incompréhensible.
- Le rootfs **n'est plus supprimé** en cas d'échec : le nouvel essai reprend au
  stage des paquets, sans retélécharger 35 Mo, et sans obliger l'utilisateur à
  supprimer la distribution à la main.

### 3.3 Alpine : repli HTTP explicite après un échec TLS

Sur l'appareil ARMv7, `apk update` échoue sur `tls_post_process_server_certificate:
certificate verify failed`. La commande d'installation tente maintenant HTTPS en
premier, puis **une seule fois** le même dépôt en HTTP :

```
(apk update || (sed -i 's|https://|http://|g' /etc/apk/repositories && apk update)) && apk add …
```

Les paquets Alpine restent vérifiés par **signature** par `apk` : c'est
l'authentification du transport qui est abandonnée, pas l'intégrité des
paquets. Les deux sorties sont conservées dans le journal. Si ce repli ne suffit
pas, le journal dira si le problème est réseau, horloge ou magasin de
certificats.

## 4. Procédure de test sur l'appareil ARMv7

1. Installer l'APK **par-dessus** l'existant (ne pas désinstaller : les données
   et les sessions sont dans le stockage privé).
2. Lancer l'installation d'Alpine. Observer le message d'erreur **complet**.
3. Ouvrir **Paramètres → Environnements d'exécution → Alpine → Journal
   d'installation** et copier le journal.
4. Refaire la même opération pour Debian.
5. Transmettre les deux journaux : ils contiennent le test des primitives, les
   diagnostics PRoot et la sortie complète de `apk`/`apt`.

Points à vérifier côté téléphone si l'erreur TLS persiste : date et heure
automatiques, réseau (Wi-Fi/mobile), portail captif ou proxy TLS, et
`Build.SUPPORTED_ABIS.first()` (l'ABI choisie pour le rootfs doit correspondre
aux bibliothèques natives réellement installées).

## 5. Limites connues

- Le repli HTTP d'Alpine est un compromis de sécurité explicite : il ne
  contourne **pas** la vérification de signature des paquets, mais il n'est pas
  activé par défaut pour les autres distributions.
- La correction Debian s'appuie sur la pile réseau de l'HÔTE : si le téléphone
  n'a aucune connectivité, le chemin hors-ligne est impossible et le repli en
  ligne est utilisé, avec les mêmes symptômes qu'avant.
- Le guest a toujours besoin de PRoot pour exécuter `dpkg` : les avertissements
  `chown`/`chmod` de dpkg peuvent persister sur un émulateur dont le noyau
  restreint `ptrace`. Ce sont des avertissements, pas des erreurs fatales.
