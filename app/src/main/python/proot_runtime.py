"""On-demand, checksummed Linux root filesystems and PRoot execution for NEMESIS."""
import hashlib
import ctypes
import errno
import json
import os
import re
import shutil
import subprocess
import tarfile
import ssl
import certifi
import threading
import time
import traceback
import socket
import ipaddress
import urllib.request
import urllib.parse
from pathlib import Path

ALPINE_VERSION = "3.22.8"
DISTRIBUTIONS = ("alpine", "debian")
# Downloads are never bundled in the APK. Checksums are pinned per Android ABI.
SPECS = {
    "alpine": {
        "version": "3.22.8", "label": "Alpine Linux", "root": "alpine-3.22.8",
        "download_estimate": {"x86": 3_700_000, "x86_64": 3_700_000, "arm64-v8a": 3_700_000, "armeabi-v7a": 3_500_000},
        "disk_estimate": {"x86": 220_000_000, "x86_64": 220_000_000, "arm64-v8a": 220_000_000, "armeabi-v7a": 210_000_000},
        "toolchain_download_estimate": {"x86": 150_000_000, "x86_64": 150_000_000, "arm64-v8a": 150_000_000, "armeabi-v7a": 145_000_000},
        "strip": 0, "mode": "r:gz",
        "arches": {
            "x86": ("x86", "8e30123124a53fde96125d4c45818f1c20adfcc32c7de7bfb0e3bc7c1b4d8f6e"),
            "x86_64": ("x86_64", "2d7f46af9b7e86ad64ebeba3fe9b75b62dd5b259e708afb2ab21268f627696e5"),
            "arm64-v8a": ("aarch64", "36091efacd083c1cf5a8c837272530bff4fc72db1fbfdef559f52de0d890bca4"),
            "armeabi-v7a": ("armhf", "1eba584655d74b4705ad2b3260aea0265644b1cc1acee10a0cba1b5d7aa8196b"),
        },
    },
    "debian": {
        "version": "13 (Trixie)", "label": "Debian 13 Stable", "root": "debian-trixie",
        "download_estimate": {"x86": 37_872_208, "x86_64": 36_725_620, "arm64-v8a": 35_407_044, "armeabi-v7a": 33_961_756},
        "disk_estimate": {"x86": 500_000_000, "x86_64": 500_000_000, "arm64-v8a": 480_000_000, "armeabi-v7a": 460_000_000},
        "toolchain_download_estimate": {"x86": 320_000_000, "x86_64": 320_000_000, "arm64-v8a": 310_000_000, "armeabi-v7a": 290_000_000},
        "strip": 1, "mode": "r:xz",
        "arches": {
            "x86": ("i686", "8579087c23d759f3ded2c88d8eb707fe9efe524330c8894828f3246cdcc36117"),
            "x86_64": ("x86_64", "e2edc15363395936cf0cba8c440a108458dba58fb496d3d962909d7a8d9777ae"),
            "arm64-v8a": ("aarch64", "cda75346f2c9e09e8a802665745b5a7e2bd6d8584dbf1c86c8c57ef54c4e2d3c"),
            "armeabi-v7a": ("arm", "868ad59b44098d7175819bdabda0dcd98ddc67af15c153cc9e22797ac77f9dd3"),
        },
    },
}

_ROOTFS_LOCK = threading.RLock()
_FILES_DIR = ""
_NATIVE_LIB_DIR = ""
_ANDROID_ABI = ""
_DNS_SERVERS = ""
_INSTALL_PROGRESS = {}
_INSTALL_PROGRESS_LOCK = threading.RLock()


def _set_install_progress(distro, phase, percent, detail="", **extra):
    state={"success":phase != "error", "id":distro, "phase":phase,
           "percent":max(0,min(100,int(percent))), "detail":str(detail)[:300], **extra}
    with _INSTALL_PROGRESS_LOCK: _INSTALL_PROGRESS[distro]=state


def installation_progress(distro):
    key=str(distro or "").strip().lower()
    with _INSTALL_PROGRESS_LOCK:
        state=dict(_INSTALL_PROGRESS.get(key,{"success":True,"id":key,"phase":"idle","percent":0,"detail":""}))
    return _data(state)


def _data(value):
    return json.dumps(value, ensure_ascii=False)


def _sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


#: Paquets installés dans l'environnement. Les listes sont partagées par
#: l'installation en ligne (apk/apt dans le guest) et par l'installation
#: hors-ligne (téléchargement des paquets par l'hôte, puis dpkg/apt --no-download).
PACKAGES = {
    "alpine": ["bash", "ca-certificates", "curl", "git", "python3", "py3-pip", "py3-virtualenv",
               "nodejs", "npm", "openssh-client-default", "sudo", "shadow", "coreutils",
               "findutils", "grep", "sed", "tar"],
    "debian": ["bash", "ca-certificates", "curl", "git", "python3", "python3-pip", "python3-venv",
               "nodejs", "npm", "openssh-client", "sudo", "passwd", "zip", "unzip", "coreutils",
               "findutils", "grep", "sed", "tar"],
}

#: Format des lignes « --print-uris » d'apt :
#:   'http://…/paquet.deb' paquet.deb 12345 SHA256:abcdef…
_URI_LINE = re.compile(r"^'([^']+)'\s+(\S+)\s+(\d+)\s+SHA256:([0-9a-fA-F]+)")


def _spec(distro):
    distro = str(distro or "").strip().lower()
    if distro not in SPECS:
        raise ValueError("Distribution non prise en charge")
    spec = SPECS[distro]
    arch_info = spec["arches"].get(_ANDROID_ABI)
    if not arch_info:
        raise ValueError("Cette distribution n'est pas disponible pour l'architecture de cet appareil")
    return distro, spec, arch_info


def _target_path(distro):
    _, spec, (arch, _) = _spec(distro)
    return Path(_FILES_DIR) / "nemesis-linux" / (spec["root"] + "-" + arch)


def _is_ready(path, version):
    marker = path / ".nemesis-rootfs-ready"
    shell = path / "bin/sh"
    try:
        parts = marker.read_text(encoding="utf-8").strip().rsplit(None, 1)
        return len(parts) == 2 and parts[0] == version and (shell.is_file() or shell.is_symlink())
    except OSError:
        return False


def _installed_size(path):
    total = 0
    try:
        for item in path.rglob("*"):
            try:
                if item.is_file() and not item.is_symlink():
                    total += item.stat().st_size
            except OSError:
                continue
    except OSError:
        pass
    return total


def _installed_size_cached(path, version):
    marker = path / ".nemesis-rootfs-ready"
    try:
        parts = marker.read_text(encoding="utf-8").strip().rsplit(None, 1)
        if len(parts) == 2 and parts[0] == version:
            return max(0, int(parts[1]))
    except (OSError, ValueError):
        pass
    return 0


def _toolchain_ready(path):
    try: return (Path(path) / ".nemesis-toolchain-v3").read_text(encoding="ascii").strip() == "3"
    except OSError: return False


def _mark_toolchain_ready(path):
    (Path(path) / ".nemesis-toolchain-v3").write_text("3\n", encoding="ascii")
    marker = Path(path) / ".nemesis-rootfs-ready"
    try:
        version = marker.read_text(encoding="utf-8").strip().split()[0]
        marker.write_text("%s %d\n" % (version, _installed_size(path)), encoding="utf-8")
    except (OSError, IndexError): pass


def _activate_rootfs(staging, target):
    """Atomically activate a completed rootfs without replacing an existing target."""
    staging = Path(staging)
    target = Path(target)
    if target.exists() or target.is_symlink():
        raise RuntimeError(
            "Le rootfs cible existe déjà ; il est conservé sans modification. "
            "Le staging reste disponible pour diagnostic ou reprise.")
    # renameat2(RENAME_NOREPLACE) closes the check/rename race. Fail closed if
    # the Android kernel/libc does not support the no-replace operation.
    try:
        libc = ctypes.CDLL(None, use_errno=True)
        renameat2 = libc.renameat2
    except (AttributeError, OSError) as exc:
        raise RuntimeError("Activation atomique sans remplacement non disponible ; staging conservé.") from exc
    renameat2.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
    renameat2.restype = ctypes.c_int
    result = renameat2(-100, os.fsencode(str(staging)), -100, os.fsencode(str(target)), 1)
    if result != 0:
        error = ctypes.get_errno()
        if error == errno.EEXIST:
            raise RuntimeError("Le rootfs cible est apparu pendant l’activation ; il est conservé.")
        raise OSError(error, os.strerror(error), str(target))


def _cached_deb_matches(path, size, expected_sha256):
    """Accept a cached Debian archive only when size and index SHA-256 match."""
    path = Path(path)
    try:
        return (path.is_file() and not path.is_symlink() and
                path.stat().st_size == int(size) and
                _sha256(path).lower() == str(expected_sha256).lower())
    except (OSError, TypeError, ValueError):
        return False


def _valid_deb_uri(url, name, size, sha256):
    """Reject unsafe archive paths and incomplete/untrusted APT URI metadata."""
    try:
        parsed = urllib.parse.urlsplit(str(url))
        return (parsed.scheme.lower() in ("http", "https") and bool(parsed.hostname) and
                parsed.username is None and parsed.password is None and
                bool(name) and name not in (".", "..") and
                "/" not in name and "\\" not in name and
                not any(ord(char) < 32 for char in name) and
                int(size) > 0 and re.fullmatch(r"[0-9a-fA-F]{64}", str(sha256)) is not None)
    except (TypeError, ValueError):
        return False


def status(distro):
    distro = str(distro or "").strip().lower()
    if distro not in SPECS:
        return _data({"success": False, "error": "Distribution non prise en charge"})
    spec = SPECS[distro]
    arch_info = spec["arches"].get(_ANDROID_ABI)
    if not arch_info:
        return _data({"success": True, "id": distro, "supported": False, "installed": False,
                      "error": "Non disponible sur cette architecture", "version": spec["version"]})
    target = _target_path(distro)
    installed = _is_ready(target, spec["version"])
    actual = _installed_size_cached(target, spec["version"]) if installed else 0
    archive = spec["download_estimate"].get(_ANDROID_ABI, 0)
    tools_download = spec.get("toolchain_download_estimate", {}).get(_ANDROID_ABI, 0)
    return _data({"success": True, "id": distro, "label": spec["label"], "version": spec["version"],
                  "supported": True, "installed": installed, "toolchain_ready": _toolchain_ready(target) if installed else False,
                  "download_bytes": archive + tools_download,
                  "installed_bytes": actual or spec["disk_estimate"].get(_ANDROID_ABI, 0),
                  "actual_installed_bytes": actual, "archive_bytes": archive, "toolchain_download_bytes": tools_download})


def _archive_url(distro, spec, arch):
    if distro == "alpine":
        name = "alpine-minirootfs-%s-%s.tar.gz" % (spec["version"], arch)
        return "https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/%s/%s" % (arch, name), name
    filename = "debian-trixie-%s-pd-v4.26.0.tar.xz" % arch
    return "https://github.com/termux/proot-distro/releases/download/v4.26.0/" + filename, filename


def _extract_archive(archive, destination, strip_components=0, mode="r:*", progress=None):
    destination.mkdir(parents=True, exist_ok=False)
    root = os.path.realpath(str(destination))
    with tarfile.open(str(archive), mode) as tf:
        safe = []
        by_relative = {}
        for member in tf.getmembers():
            name = member.name
            if name.startswith("/") or "\\" in name or any(ord(c) < 32 for c in name):
                raise ValueError("Archive contient un chemin interdit")
            parts = [part for part in name.split("/") if part not in ("", ".")]
            if any(part == ".." for part in parts):
                raise ValueError("Archive sort du dossier d'installation")
            if len(parts) <= strip_components:
                continue
            parts = parts[strip_components:]
            relative = "/".join(parts)
            path = os.path.join(str(destination), *parts)
            parent = os.path.dirname(path)
            os.makedirs(parent, exist_ok=True)
            parent_real = os.path.realpath(parent)
            if parent_real != root and not parent_real.startswith(root + os.sep):
                raise ValueError("Chemin archive hors du dossier privé")
            safe.append((member, relative, path))
            by_relative[relative] = (member, path)
        directories, symlinks, hardlinks = [], [], []
        progress_step=max(1,len(safe)//25)
        for index,(member, relative, path) in enumerate(safe,1):
            member.uid = member.gid = None
            if member.isdir():
                os.makedirs(path, exist_ok=True)
                directories.append((path, member.mode & 0o777))
            elif member.isfile():
                stream = tf.extractfile(member)
                if stream is None:
                    raise IOError("Donnée manquante dans l'archive")
                with stream, open(path, "wb") as output:
                    shutil.copyfileobj(stream, output)
                os.chmod(path, member.mode & 0o777)
            elif member.issym():
                symlinks.append((path, relative, member.linkname))
            elif member.islnk():
                hardlinks.append((path, member.linkname))
            # Device nodes and FIFOs are intentionally omitted. Android's /dev
            # nodes are bind-mounted when the guest runs.
            if progress and (index % progress_step == 0 or index == len(safe)):
                progress(index,len(safe))
        for path, linkname in hardlinks:
            raw_parts = [part for part in linkname.split("/") if part not in ("", ".")]
            if linkname.startswith("/") or any(part == ".." for part in raw_parts):
                raise ValueError("Lien dur hors du rootfs interdit")
            candidates = ["/".join(raw_parts)]
            if len(raw_parts) > strip_components:
                candidates.insert(0, "/".join(raw_parts[strip_components:]))
            source = next((by_relative[c][1] for c in candidates if c in by_relative and by_relative[c][0].isfile()), None)
            if source and os.path.isfile(source) and not os.path.lexists(path):
                os.link(source, path)
        for path, relative, target in symlinks:
            if not target or "\x00" in target:
                raise ValueError("Lien symbolique invalide")
            parent = os.path.dirname(path)
            # Do not allow another archive link to turn this parent into a host escape.
            check = parent
            while check != str(destination) and check.startswith(str(destination) + os.sep):
                if os.path.islink(check):
                    raise ValueError("Lien parent de rootfs interdit")
                check = os.path.dirname(check)
            if target.startswith("/"):
                os.symlink(target, path)
            else:
                # Interpret '..' lexically inside the guest root. realpath() would
                # follow absolute guest links as host-absolute paths and reject
                # valid links through /etc, /usr, or /var by mistake.
                parent_relative = os.path.relpath(parent, str(destination))
                resolved_relative = os.path.normpath(os.path.join(parent_relative, target))
                if resolved_relative == ".." or resolved_relative.startswith(".." + os.sep):
                    raise ValueError("Lien symbolique sortant interdit")
                os.symlink(target, path)
        for path, permissions in reversed(directories):
            os.chmod(path, permissions)


def _prime_debian_mirror_hosts(guest):
    """Pin current mirror IPv4s resolved by Android into the PRoot guest's hosts file."""
    entries=[]
    for host in ("deb.debian.org", "security.debian.org"):
        try:
            results=socket.getaddrinfo(host,80,socket.AF_INET,socket.SOCK_STREAM)
        except OSError:
            continue
        for result in results:
            try: address=str(ipaddress.IPv4Address(result[4][0]))
            except (IndexError,ValueError): continue
            line=address+" "+host
            if line not in entries: entries.append(line)
    if not entries:
        return entries
    hosts = Path(guest) / "etc/hosts"
    try:
        existing = hosts.read_text(encoding="ascii", errors="replace")
    except OSError:
        existing = ""
    fresh = [line for line in entries if line not in existing]
    if not fresh:
        return entries
    with hosts.open("a",encoding="ascii") as stream:
        stream.write("\n"+"\n".join(fresh)+"\n")
    return fresh


def _seed_guest_ca_bundle(guest):
    """Seed the Linux guest with the app-bundled Mozilla roots before its first HTTPS package request."""
    source = Path(certifi.where())
    bundle = source.read_bytes()
    if len(bundle) < 100_000 or b"-----BEGIN CERTIFICATE-----" not in bundle:
        raise RuntimeError("Le bundle de certificats HTTPS de l’application est absent ou invalide.")
    target = Path(guest) / "etc" / "ssl" / "certs" / "ca-certificates.crt"
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.is_symlink():
        raise RuntimeError("Chemin inattendu dans le magasin de certificats du rootfs.")
    target.write_bytes(bundle)
    return len(bundle)


# ---------------------------------------------------------------------------
# Journal d'installation
# ---------------------------------------------------------------------------
# Les diagnostics de PRoot (par exemple « Detected broken PTRACE_POKEDATA »,
# « ptrace(POKEDATA) », « integer under/overflow detected ») sont écrits sur
# stderr, c'est-à-dire AU DÉBUT de la sortie. Le code ne conservait que les
# 4 000 derniers caractères : ces messages étaient systématiquement coupés.
# Le journal complet est désormais écrit sur disque et renvoyé avec l'erreur.

_LOG_DIR_NAME = "logs"


def _log_dir():
    return Path(_FILES_DIR) / "nemesis-linux" / _LOG_DIR_NAME


def _safe_name(name):
    """Nom de fichier sûr pour un identifiant de distribution ou un chemin."""
    cleaned = re.sub(r"[^A-Za-z0-9_.-]+", "-", str(name or "")).strip("-")
    return cleaned[:60] or "unknown"


def _append_log(distro, label, text):
    """Ajoute un bloc horodaté au journal d'installation. Rend le chemin du journal."""
    try:
        directory = _log_dir()
        directory.mkdir(parents=True, exist_ok=True)
        path = directory / ("install-%s.log" % _safe_name(distro))
        stamp = time.strftime("%Y-%m-%d %H:%M:%S")
        with path.open("a", encoding="utf-8", errors="replace") as stream:
            stream.write("\n===== %s [%s] =====\n%s\n" % (stamp, label, text or ""))
        return str(path)
    except OSError:
        return ""


def install_log(distro, maximum=200000):
    """Rend le journal d'installation de la distribution (lecture seule)."""
    distro = str(distro or "").strip().lower()
    if distro not in SPECS:
        return _data({"success": False, "error": "Distribution non prise en charge"})
    path = _log_dir() / ("install-%s.log" % _safe_name(distro))
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return _data({"success": False, "id": distro, "log": "", "error": "Aucun journal d'installation"})
    text = text[-max(1000, int(maximum)):]
    return _data({"success": True, "id": distro, "path": str(path), "log": text})


def _proot_env(libs, tmp, extra=None):
    env = os.environ.copy()
    env.update({"PROOT_LOADER": str(libs / "proot-loader"), "PROOT_TMP_DIR": str(tmp),
                "PROOT_NO_SECCOMP": "1", "LD_LIBRARY_PATH": str(libs) + ":" + str(_NATIVE_LIB_DIR),
                "TMPDIR": str(tmp), "HOME": "/root",
                "PATH": "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "HOSTNAME": "nemesis", "DEBIAN_FRONTEND": "noninteractive",
                # PRoot note ses diagnostics à partir du niveau 1 : on le force
                # pour que les messages du moteur soient toujours capturés.
                "PROOT_VERBOSE": "1"})
    if extra:
        env.update(extra)
    return env


def _ensure_native_libs():
    """Copie les bibliothèques PRoot de l'APK vers le dossier de travail."""
    native_dir = Path(_NATIVE_LIB_DIR)
    data_dir = Path(_FILES_DIR) / "nemesis-linux"
    libs = data_dir / "libs"
    tmp = data_dir / "tmp"
    libs.mkdir(parents=True, exist_ok=True)
    tmp.mkdir(parents=True, exist_ok=True)
    for name in ("libproot.so", "libproot-loader.so", "libtalloc.so", "libandroid-shmem.so"):
        source = native_dir / name
        if not source.is_file():
            raise RuntimeError("Bibliothèque PRoot absente: " + name)
        target = libs / ("proot-loader" if name == "libproot-loader.so" else name)
        if not target.exists() or target.stat().st_size != source.stat().st_size:
            shutil.copyfile(source, target)
        os.chmod(target, 0o700 if name in ("libproot.so", "libproot-loader.so") else 0o600)
    versioned = libs / "libtalloc.so.2"
    if not versioned.exists():
        os.symlink("libtalloc.so", str(versioned))
    return libs, tmp


def _guest_args(guest, command, libs, extra_args=()):
    args = [str(libs / "libproot.so"), "-0", "-r", str(guest),
            "-b", "/dev/null", "-b", "/dev/zero", "-b", "/dev/urandom", "-b", "/dev/random",
            "-b", "/proc", "-b", "/sys", "-w", "/root"]
    args.extend(extra_args)
    args.extend(["/bin/sh", "-c", command])
    return args


def _run_guest(guest, command, timeout=1800, label="guest", libs=None, tmp=None, extra_args=(),
               distro="guest"):
    """Exécute une commande dans le guest et rend (code de sortie, sortie complète)."""
    if libs is None or tmp is None:
        libs, tmp = _ensure_native_libs()
    env = _proot_env(libs, tmp)
    args = _guest_args(guest, command, libs, extra_args)
    try:
        completed = subprocess.run(args, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   timeout=timeout, check=False)
        output = completed.stdout.decode("utf-8", "replace")
        code = completed.returncode
    except subprocess.TimeoutExpired as exc:
        output = (exc.stdout or b"").decode("utf-8", "replace") if isinstance(exc.stdout, bytes) else str(exc.stdout or "")
        output += "\n[ délai dépassé après %s s ]" % timeout
        code = -1
    _append_log(distro, label, "$ %s\n%s" % (command[:400], output))
    return code, output


def _download_to(url, target, expected_sha256=""):
    """Télécharge un paquet depuis l'HÔTE (Python + certifi) et vérifie son SHA-256."""
    request = urllib.request.Request(url, headers={"User-Agent": "NEMESIS-Android/PRoot",
                                                   "Accept-Encoding": "identity"})
    digest = hashlib.sha256()
    temporary = str(target) + ".part"
    with urllib.request.urlopen(request, timeout=90,
                                context=ssl.create_default_context(cafile=certifi.where())) as response:
        with open(temporary, "wb") as output:
            while True:
                chunk = response.read(256 * 1024)
                if not chunk:
                    break
                output.write(chunk)
                digest.update(chunk)
    if expected_sha256:
        actual = digest.hexdigest()
        if actual.lower() != expected_sha256.lower():
            try:
                os.unlink(temporary)
            except OSError:
                pass
            raise RuntimeError("SHA-256 inattendu pour %s" % os.path.basename(str(target)))
    os.replace(temporary, str(target))


def _parse_uris(output):
    """Extrait (url, nom, taille, sha256) des lignes « --print-uris » d'apt."""
    found = []
    for line in (output or "").splitlines():
        line = line.strip()
        match = _URI_LINE.match(line)
        if not match:
            continue
        url, name, size, sha = match.group(1), match.group(2), int(match.group(3)), match.group(4)
        found.append((url, name, size, sha))
    return found


def _prepare_debian_packages(guest, progress=None):
    """Télécharge les paquets Debian depuis l'HÔTE et les place dans le cache apt.

    Pourquoi : le guest n'a plus besoin de faire de HTTP ni de TLS. Le
    téléchargement utilise le Python de l'application et son bundle certifi,
    c'est-à-dire la même chaîne qui télécharge déjà le rootfs et qui fonctionne
    sur toutes les ABIs. L'installation se fait ensuite hors-ligne dans le
    guest (`apt-get install --no-download`), ce qui supprime le chemin réseau
    — et donc le syscall en échec — au moment où dpkg/apt écrivent dans
    /var/cache/apt/archives.

    Rend un dictionnaire de diagnostic ; `ok` vaut False si le chemin hors-ligne
    est indisponible (l'appelant retombe alors sur l'installation en ligne).
    """
    options = "-o APT::Sandbox::User=root -o Acquire::Retries=2"
    if progress:
        progress("packages", 70, "Mise à jour des index Debian (dans le guest)…")
    code, output = _run_guest(guest, "apt-get %s update" % options, timeout=900, label="apt-update",
                              distro="debian")
    if code != 0:
        return {"ok": False, "stage": "apt-update", "detail": output[-1500:]}
    if progress:
        progress("packages", 72, "Calcul de la liste des paquets à télécharger…")
    code, output = _run_guest(
        guest,
        "apt-get %s --print-uris -y install %s" % (options, " ".join(PACKAGES["debian"])),
        timeout=900, label="apt-uris", distro="debian")
    if code != 0:
        return {"ok": False, "stage": "apt-uris", "detail": output[-1500:]}
    uris = _parse_uris(output)
    if not uris:
        return {"ok": False, "stage": "apt-uris", "detail": output[-1500:]}
    archives = Path(guest) / "var" / "cache" / "apt" / "archives"
    archives.mkdir(parents=True, exist_ok=True)
    (archives / "partial").mkdir(parents=True, exist_ok=True)
    downloaded = 0
    failures = []
    for index, (url, name, size, sha) in enumerate(uris, 1):
        if not _valid_deb_uri(url, name, size, sha):
            failures.append("métadonnées APT invalides ou chemin de paquet interdit")
            continue
        target = archives / name
        try:
            if _cached_deb_matches(target, size, sha):
                downloaded += 1
                continue
            # A wrong-size, corrupt, or symlink cache entry is never trusted.
            if target.exists() or target.is_symlink():
                target.unlink()
            _download_to(url, target, sha)
            if not _cached_deb_matches(target, size, sha):
                raise RuntimeError("Taille ou SHA-256 incorrect après téléchargement")
            downloaded += 1
        except Exception as exc:
            failures.append("%s : %s" % (name, str(exc)[:90]))
        if progress and (index % 5 == 0 or index == len(uris)):
            progress("packages", 72 + int(3 * index / max(1, len(uris))),
                     "Paquets téléchargés par l’hôte : %d / %d" % (downloaded, len(uris)))
    return {"ok": downloaded > 0 and not failures, "downloaded": downloaded, "total": len(uris),
            "failures": failures[:5]}


def _proot_self_test(guest, distro="guest"):
    """Teste les primitives PRoot essentielles et rend le résultat (lecture/écriture).

    Objectif : distinguer un problème réseau d'un problème de traduction de
    chemins par PRoot, qui se manifestent tous deux par « 14: Bad address ».
    """
    script = ("cd /tmp || exit 1; "
              "echo nemesis > nemesis-selftest.txt 2>/dev/null || echo FAIL:write; "
              "cat nemesis-selftest.txt >/dev/null 2>&1 || echo FAIL:read; "
              "chmod 0644 nemesis-selftest.txt 2>/dev/null || echo FAIL:chmod; "
              "chown 0:0 nemesis-selftest.txt 2>/dev/null || echo FAIL:chown; "
              "mv nemesis-selftest.txt nemesis-selftest2.txt 2>/dev/null || echo FAIL:rename; "
              "ln -s nemesis-selftest2.txt nemesis-selftest-link 2>/dev/null || echo FAIL:symlink; "
              "rm -f nemesis-selftest2.txt nemesis-selftest-link; "
              "(getent hosts deb.debian.org >/dev/null 2>&1 && echo OK:dns) || echo FAIL:dns; "
              "command -v wget >/dev/null 2>&1 && "
              "(wget -q -T 20 -O /dev/null https://deb.debian.org/debian/ && echo OK:https) || echo SKIP:https")
    code, output = _run_guest(guest, script, timeout=180, label="self-test", distro=distro)
    results = {"exit_code": code}
    for line in (output or "").splitlines():
        line = line.strip()
        if line.startswith("FAIL:") or line.startswith("OK:") or line.startswith("SKIP:"):
            state, _, name = line.partition(":")
            results[name.strip()] = state.strip().lower()
    return results


def _bootstrap_tools(distro, guest, progress=None):
    """Install a usable toolchain and the app-managed nemesis account in a rootfs."""
    libs, base_tmp = _ensure_native_libs()
    tmp = base_tmp / ("bootstrap-" + str(distro))
    tmp.mkdir(parents=True, exist_ok=True)
    resolver = guest / "etc/resolv.conf"
    try: resolver.unlink()
    except FileNotFoundError: pass
    dns = [x.strip() for x in _DNS_SERVERS.split(",") if x.strip()]
    if dns: resolver.write_text("".join("nameserver " + x + "\n" for x in dns), encoding="ascii")
    env = _proot_env(libs, tmp)
    if distro == "alpine":
        create_user="(id nemesis >/dev/null 2>&1 || adduser -D -s /bin/sh -h /home nemesis)"
    elif distro == "debian":
        create_user="(id nemesis >/dev/null 2>&1 || useradd -m -s /bin/sh nemesis)"
    else:
        raise ValueError("Distribution non prise en charge")
    identity=(create_user + " && printf 'nemesis:nemesis123\\n' | chpasswd && mkdir -p /etc/sudoers.d /home/nemesis && "
              "printf 'nemesis ALL=(ALL:ALL) ALL\\n' > /etc/sudoers.d/90-nemesis && chmod 0440 /etc/sudoers.d/90-nemesis && "
              "printf 'nemesis\\n' > /etc/hostname && "
              "(grep -Fq nemesis /etc/hosts || printf '127.0.0.1 localhost nemesis\\n' >> /etc/hosts) && "
              "(grep -Fq '@includedir /etc/sudoers.d' /etc/sudoers || printf '\\n@includedir /etc/sudoers.d\\n' >> /etc/sudoers) && chmod 0440 /etc/sudoers && "
              "visudo -c >/dev/null")
    if distro == "alpine":
        # HTTPS d'abord. En cas d'échec de vérification de certificat (erreur
        # constatée sur certains appareils 32 bits), on rejoue une fois avec le
        # miroir en HTTP : les paquets restent vérifiés par la SIGNATURE apk,
        # seule l'authentification du transport est abandonnée. Le journal
        # d'installation conserve les deux sorties.
        alpine_packages = " ".join(PACKAGES["alpine"])
        fallback = ("sed -i 's|https://|http://|g' /etc/apk/repositories && apk update")
        command = ("(apk update || (" + fallback + ")) && apk add --no-cache " + alpine_packages +
                   " && " + identity + " && rm -rf /var/cache/apk/*")
    elif distro == "debian":
        # Installation hors-ligne : les paquets ont été téléchargés par l'HÔTE
        # et déposés dans /var/cache/apt/archives. Le guest n'a plus de réseau
        # à ce stade. Si un paquet manque (cache incomplet), on repasse en ligne.
        apt_opts = "-o APT::Sandbox::User=root -o Acquire::Retries=3"
        debian_packages = " ".join(PACKAGES["debian"])
        command = ("(apt-get " + apt_opts + " install -y --no-download --no-install-recommends " + debian_packages +
                   " || apt-get " + apt_opts + " install -y --no-install-recommends " + debian_packages +
                   ") && " + identity + " && apt-get " + apt_opts + " clean && rm -rf /var/lib/apt/lists/*")
    else:
        raise ValueError("Distribution non prise en charge")
    # The app's current Mozilla CA set is newer than some architecture-specific
    # minirootfs bundles; seed it before apk/apt makes its first HTTPS request.
    _seed_guest_ca_bundle(guest)
    if distro == "debian":
        _prime_debian_mirror_hosts(guest)
    if progress: progress("packages",76,"Installation des paquets et configuration de l’utilisateur nemesis…")
    args = [str(libs / "libproot.so"), "-0", "-r", str(guest),
            "-b", "/dev/null", "-b", "/dev/zero", "-b", "/dev/urandom", "-b", "/dev/random",
            "-b", "/proc", "-b", "/sys", "-w", "/root", "/bin/sh", "-c", command]
    completed = subprocess.run(args, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               timeout=1800, check=False)
    output = completed.stdout.decode("utf-8", "replace")
    log_path = _append_log(distro, "packages", output)
    if completed.returncode != 0:
        # On conserve le DÉBUT de la sortie : les diagnostics de PRoot
        # (« Detected broken PTRACE_POKEDATA », « ptrace(POKEDATA) »…) y sont
        # écrits, alors qu'ils étaient systématiquement coupés par la seule
        # conservation des 4 000 derniers caractères.
        raise RuntimeError(
            "Installation/configuration des paquets échouée (code %d).\n"
            "Journal complet : %s\n\n--- début de la sortie ---\n%s\n--- fin de la sortie ---\n%s" % (
                completed.returncode, log_path or "(non écrit)", output[:3000], output[-3000:]))
    verify_command=("command -v git python3 node npm sudo >/dev/null && "
                    "grep -qx nemesis /etc/hostname && id nemesis && "
                    "printf 'nemesis123\\n' | su -s /bin/sh -c 'sudo -S -p NEMESIS_PROMPT id -un' nemesis")
    verify = subprocess.run(args[:-3] + ["/bin/sh", "-c", verify_command], env=env,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60, check=False)
    verify_output = verify.stdout.decode("utf-8", "replace")
    _append_log(distro, "verify", verify_output)
    if verify.returncode != 0:
        raise RuntimeError("Vérification post-installation (outils/hostname/compte/sudo) échouée (code %d):\n%s" %
                           (verify.returncode, verify_output[-2500:]))
    if progress: progress("verify",96,"Outils, hostname nemesis et accès sudo vérifiés.")


def install(distro):
    """Installe une distribution : téléchargement, extraction, paquets, configuration.

    Le rootfs téléchargé est conservé en cas d'échec de l'étape « paquets » :
    le prochain essai reprend au stage des paquets, sans retélécharger l'archive.
    """
    distro=str(distro or "").strip().lower()
    _set_install_progress(distro,"starting",1,"Vérification de l’architecture et de l’espace…")
    try:
        distro, spec, (arch, checksum) = _spec(distro)
        base = Path(_FILES_DIR) / "nemesis-linux"
        base.mkdir(parents=True, exist_ok=True)
        target = _target_path(distro)
        if _is_ready(target, spec["version"]):
            if _toolchain_ready(target):
                _set_install_progress(distro,"complete",100,"Environnement déjà installé et vérifié.")
                return status(distro)
            _set_install_progress(distro,"packages",72,"Mise à niveau des outils et configuration de nemesis…")
            with _ROOTFS_LOCK:
                if not _toolchain_ready(target):
                    _bootstrap_tools(distro,target,lambda phase,p,detail:_set_install_progress(distro,phase,p,detail))
                    _mark_toolchain_ready(target)
            result=json.loads(status(distro));result["tools_updated"]=True
            _set_install_progress(distro,"complete",100,"Outils et compte nemesis configurés.")
            return _data(result)
        staging = base / (spec["root"] + "-" + arch + ".installing")
        archive_bytes = 0
        with _ROOTFS_LOCK:
            # ---- réutilisation d'un rootfs déjà extrait ----------------------
            reusable = staging.exists() and (staging/"bin/sh").exists() and (staging/"etc").is_dir()
            if reusable and (target.exists() or target.is_symlink()):
                raise RuntimeError(
                    "Le rootfs cible et le staging de reprise existent tous deux ; "
                    "aucun fichier n’a été supprimé. Le staging est conservé.")
            if not reusable:
                if target.exists() or target.is_symlink():
                    # Un rootfs extrait mais jamais finalisé est repris plutôt
                    # que rejeté : l'utilisateur n'a plus à le supprimer à la main.
                    if target.is_dir() and (target/"bin/sh").exists() and (target/"etc").is_dir():
                        _set_install_progress(distro,"packages",70,"Rootfs déjà téléchargé : reprise de la configuration…")
                        _bootstrap_tools(distro,target,lambda phase,p,detail:_set_install_progress(distro,phase,p,detail))
                        (target/".nemesis-rootfs-ready").write_text(
                            "%s %d\n"%(spec["version"],_installed_size(target)),encoding="utf-8")
                        _mark_toolchain_ready(target)
                        result=json.loads(status(distro));result["resumed"]=True
                        _set_install_progress(distro,"complete",100,"Installation reprise et terminée.")
                        return _data(result)
                    raise ValueError("Installation existante incomplète; fichiers conservés. Supprimez cette distribution puis réessayez.")
                url, filename = _archive_url(distro, spec, arch)
                partial = base / (filename + ".part")
                request = urllib.request.Request(url, headers={"User-Agent": "NEMESIS-Android/PRoot", "Accept-Encoding":"identity"})
                _set_install_progress(distro,"download",3,"Connexion à la source officielle…",downloaded_bytes=0,total_bytes=0)
                with urllib.request.urlopen(request, timeout=60, context=ssl.create_default_context(cafile=certifi.where())) as response:
                    total=int(response.headers.get("Content-Length") or 0)
                    downloaded=0
                    with open(partial,"wb") as output:
                        while True:
                            chunk=response.read(256*1024)
                            if not chunk: break
                            output.write(chunk);downloaded+=len(chunk)
                            percent=3+int(45*downloaded/total) if total>0 else min(48,3+downloaded//(512*1024))
                            _set_install_progress(distro,"download",percent,"Téléchargement %.1f / %s Mo" %
                                (downloaded/1_000_000,"%.1f"%(total/1_000_000) if total>0 else "?"),
                                downloaded_bytes=downloaded,total_bytes=total)
                archive_bytes=partial.stat().st_size
                _set_install_progress(distro,"verify",51,"Vérification SHA-256…",downloaded_bytes=archive_bytes,total_bytes=archive_bytes)
                actual_hash=_sha256(partial)
                if actual_hash!=checksum:
                    partial.unlink(missing_ok=True)
                    raise ValueError("Vérification SHA-256 échouée; archive téléchargée refusée.")
                def extraction_progress(done,total):
                    percent=54+int(19*done/max(1,total))
                    _set_install_progress(distro,"extract",percent,"Extraction sécurisée : %d / %d entrées"%(done,total))
                _set_install_progress(distro,"extract",53,"Extraction sécurisée du rootfs…")
                if staging.exists():
                    shutil.rmtree(staging, ignore_errors=True)
                _extract_archive(partial,staging,spec["strip"],spec["mode"],extraction_progress)
                try: partial.unlink()
                except OSError: pass
            # ---- magasin de certificats et résolution -----------------------
            # Le bundle CA de l'application et les adresses des miroirs Debian
            # doivent être en place AVANT toute requête du guest : sinon
            # l'index apt est lu avec le vieux magasin du rootfs.
            try:
                _seed_guest_ca_bundle(staging)
            except Exception as exc:
                _append_log(distro,"ca-bundle","echec: "+str(exc)[:200])
            if distro == "debian":
                try:
                    _prime_debian_mirror_hosts(staging)
                except Exception as exc:
                    _append_log(distro,"mirror-hosts","echec: "+str(exc)[:200])

            # ---- diagnostics PRoot ------------------------------------------
            _set_install_progress(distro,"packages",68,"Test des primitives PRoot (fichiers, DNS)…")
            try:
                selftest=_proot_self_test(staging, distro)
            except Exception as exc:
                selftest={"error":str(exc)[:200]}
            _append_log(distro,"self-test",str(selftest))
            # Une primitive fondamentale en échec (écriture ou lecture) signifie
            # que PRoot ne peut pas faire tourner le guest sur cet appareil :
            # inutile de lancer l'installation des paquets, on s'arrête ici avec
            # le nom exact de la primitive fautive.
            broken=[name for name in ("write","read","chmod","rename") if selftest.get(name)=="fail"]
            if broken:
                raise RuntimeError(
                    "PRoot ne peut pas exécuter le guest sur cet appareil : primitive(s) en échec : %s. "
                    "Le journal complet (diagnostics du moteur PRoot) est conservé sur l'appareil." % ", ".join(broken))
            # ---- paquets ---------------------------------------------------
            offline={}
            if distro=="debian":
                offline=_prepare_debian_packages(staging,lambda phase,p,detail:_set_install_progress(distro,phase,p,detail))
                _append_log(distro,"offline-packages",str(offline))
                if not offline.get("ok"):
                    _set_install_progress(distro,"packages",74,"Téléchargement hors-ligne indisponible : installation en ligne…")
            _bootstrap_tools(distro,staging,lambda phase,p,detail:_set_install_progress(distro,phase,p,detail))
            shell=staging/"bin/sh"
            if not (shell.is_file() or shell.is_symlink()) or not (staging/"etc").is_dir():
                raise ValueError("Rootfs invalide après extraction : /bin/sh ou /etc absent.")
            actual_size=_installed_size(staging)
            (staging/".nemesis-rootfs-ready").write_text("%s %d\n"%(spec["version"],actual_size),encoding="utf-8")
            _set_install_progress(distro,"finalize",98,"Vérification finale et activation…")
            # Never remove or replace an existing rootfs: it may contain user data.
            _activate_rootfs(staging, target)
            _mark_toolchain_ready(target)
        result=json.loads(status(distro))
        result.update({"installed":True,"downloaded_bytes":archive_bytes,
                       "actual_installed_bytes":_installed_size_cached(target,spec["version"]),
                       "self_test":selftest,"offline_packages":offline})
        _set_install_progress(distro,"complete",100,"Installation et vérification terminées.")
        return _data(result)
    except Exception as exc:
        trace=traceback.format_exc()
        log=str(_log_dir() / ("install-%s.log" % distro))
        message=(str(exc)+"\n\nJournal d’installation : "+log+"\n\nDiagnostic complet :\n"+trace[-7000:])[:9000]
        _set_install_progress(distro,"error",100,"Échec : "+str(exc)[:250],error=message)
        return _data({"success":False,"installed":False,"error":message,"log":log})


def remove(distro):
    try:
        distro, spec, _ = _spec(distro)
        target = _target_path(distro)
        if target.is_symlink():
            raise ValueError("Le chemin d'installation est un lien; refus de supprimer")
        if target.exists():
            base = (Path(_FILES_DIR) / "nemesis-linux").resolve()
            resolved = target.resolve()
            if resolved.parent != base:
                raise ValueError("Chemin d'installation invalide")
            with _ROOTFS_LOCK:
                shutil.rmtree(target)
        return _data({"success": True, "id": distro, "removed": True})
    except Exception as exc:
        return _data({"success": False, "error": str(exc)[:300]})


def execute(command, timeout_seconds=120, workspace="", distro="alpine"):
    try:
        if not command or not str(command).strip():
            raise ValueError("Commande vide")
        distro, spec, (arch, _) = _spec(distro)
        native_dir = Path(_NATIVE_LIB_DIR)
        data_dir = Path(_FILES_DIR) / "nemesis-linux"
        guest = _target_path(distro)
        if not _is_ready(guest, spec["version"]):
            raise ValueError("Cette distribution n'est pas installée")
        libs = data_dir / "libs"
        tmp = data_dir / "tmp" / distro
        libs.mkdir(parents=True, exist_ok=True)
        tmp.mkdir(parents=True, exist_ok=True)
        for name in ("libproot.so", "libproot-loader.so", "libtalloc.so", "libandroid-shmem.so"):
            source = native_dir / name
            if not source.is_file():
                raise RuntimeError("Bibliothèque PRoot absente: " + name)
            target = libs / ("proot-loader" if name == "libproot-loader.so" else name)
            if not target.exists() or target.stat().st_size != source.stat().st_size:
                shutil.copyfile(source, target)
            os.chmod(target, 0o700 if name in ("libproot.so", "libproot-loader.so") else 0o600)
        versioned = libs / "libtalloc.so.2"
        if not versioned.exists():
            os.symlink("libtalloc.so", str(versioned))
        resolver = guest / "etc/resolv.conf"
        try:
            resolver.unlink()
        except FileNotFoundError:
            pass
        dns = [x.strip() for x in _DNS_SERVERS.split(",") if x.strip()]
        resolver.write_text("".join("nameserver " + x + "\n" for x in dns), encoding="ascii")
        host_workspace = Path(workspace).resolve() if workspace else Path(_FILES_DIR) / "workspace"
        host_workspace.mkdir(parents=True, exist_ok=True)
        (guest / "workspace").mkdir(exist_ok=True)
        env = os.environ.copy()
        env.update({"PROOT_LOADER": str(libs / "proot-loader"), "PROOT_TMP_DIR": str(tmp), "PROOT_NO_SECCOMP": "1",
                    "LD_LIBRARY_PATH": str(libs) + ":" + str(native_dir), "TMPDIR": str(tmp),
                    "HOME": "/root", "PATH": "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"})
        args = [str(libs / "libproot.so"), "-0", "-r", str(guest),
                "-b", "/dev/null", "-b", "/dev/zero", "-b", "/dev/urandom", "-b", "/dev/random",
                "-b", str(host_workspace) + ":/workspace", "-w", "/workspace", "/bin/sh", "-c", str(command)]
        completed = subprocess.run(args, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   timeout=max(1, min(int(timeout_seconds), 600)), check=False)
        output = completed.stdout.decode("utf-8", "replace")[:512 * 1024]
        return _data({"success": completed.returncode == 0, "exit_code": completed.returncode,
                      "output": output, "executor": "proot", "distro": distro,
                      "version": spec["version"]})
    except subprocess.TimeoutExpired as exc:
        text = (exc.stdout or b"").decode("utf-8", "replace") if isinstance(exc.stdout, bytes) else str(exc.stdout or "")
        return _data({"success": False, "error": "Délai PRoot dépassé", "output": text[-12000:], "executor": "proot", "distro": str(distro)})
    except Exception as exc:
        return _data({"success": False, "error": "PRoot : " + str(exc)[:300], "executor": "proot", "distro": str(distro)})



_TERMINAL_SESSIONS = {}
_TERMINAL_SESSIONS_LOCK = threading.RLock()

#: Guest paths backed by host mounts; they are never read from the rootfs.
_SYNTHETIC_BINDS = ("/proc", "/sys", "/dev")


def guest_path(distro, remote_path, workspace=""):
    """Map an absolute path inside a PRoot guest to a real host path.

    ``/workspace`` is bind-mounted to the discussion workspace while commands
    run, so it maps to ``workspace``. Every other guest path lives inside the
    rootfs directory on Android storage. Synthetic ``/proc``, ``/sys`` and
    ``/dev`` mounts are refused. Returns a host ``Path`` without copying.
    """
    distro, spec, (arch, _) = _spec(distro)
    guest = _target_path(distro)
    if not _is_ready(guest, spec["version"]):
        raise ValueError("Cette distribution n'est pas installée")
    raw = str(remote_path or "").strip()
    if not raw or len(raw) > 1000 or any(ord(c) < 32 for c in raw):
        raise ValueError("Chemin distant invalide")
    path = Path(raw)
    if not path.is_absolute():
        raise ValueError("Utilise un chemin absolu dans l'environnement PRoot")
    parts = path.parts
    if len(parts) > 1 and ("/" + parts[1]) in _SYNTHETIC_BINDS:
        raise ValueError("Ce chemin système ne peut pas être exporté")
    guest_root = guest.resolve()
    if path == Path("/workspace") or Path("/workspace") in path.parents:
        root = Path(workspace).resolve() if workspace else (Path(_FILES_DIR) / "workspace").resolve()
        target = (root / path.relative_to("/workspace")).resolve()
        if target != root and root not in target.parents:
            raise ValueError("Chemin hors de l'espace de travail")
        return target
    target = (guest_root / path.relative_to("/")).resolve()
    if target != guest_root and guest_root not in target.parents:
        raise ValueError("Chemin hors de l'environnement PRoot")
    return target


def export_file(distro, remote_path, destination, workspace=""):
    """Copy a file created inside the guest to a host destination (Android)."""
    source = guest_path(distro, remote_path, workspace)
    if source.is_symlink() or not source.is_file():
        raise ValueError("Fichier introuvable dans l'environnement")
    size = source.stat().st_size
    if size > 100 * 1024 * 1024:
        raise ValueError("Fichier limité à 100 Mo")
    target = Path(destination)
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)
    return {"success": True, "path": str(target), "size": size, "executor": "proot"}


def terminal_start(distro, workspace="", columns=80, rows=24):
    import fcntl, pty, struct, termios, uuid
    master = slave = None
    process = None
    try:
        distro, spec, (arch, _) = _spec(distro)
        guest = _target_path(distro)
        if not _is_ready(guest, spec["version"]):
            raise ValueError("Cette distribution n'est pas installée")
        native_dir = Path(_NATIVE_LIB_DIR)
        data_dir = Path(_FILES_DIR) / "nemesis-linux"
        libs = data_dir / "libs"
        tmp = data_dir / "tmp" / (distro + "-terminal")
        libs.mkdir(parents=True, exist_ok=True); tmp.mkdir(parents=True, exist_ok=True)
        for name in ("libproot.so", "libproot-loader.so", "libtalloc.so", "libandroid-shmem.so"):
            source = native_dir / name
            if not source.is_file(): raise RuntimeError("Bibliothèque PRoot absente: " + name)
            target = libs / ("proot-loader" if name == "libproot-loader.so" else name)
            if not target.exists() or target.stat().st_size != source.stat().st_size: shutil.copyfile(source,target)
            os.chmod(target,0o700 if name in ("libproot.so","libproot-loader.so") else 0o600)
        versioned=libs/"libtalloc.so.2"
        if not versioned.exists(): os.symlink("libtalloc.so",str(versioned))
        resolver=guest/"etc/resolv.conf"
        try: resolver.unlink()
        except FileNotFoundError: pass
        dns=[x.strip() for x in _DNS_SERVERS.split(",") if x.strip()]
        resolver.write_text("".join("nameserver "+x+"\n" for x in dns),encoding="ascii")
        host_workspace=Path(workspace).resolve() if workspace else Path(_FILES_DIR)/"terminal-workspace"
        host_workspace.mkdir(parents=True,exist_ok=True); (guest/"workspace").mkdir(exist_ok=True)
        env=os.environ.copy(); env.update({"PROOT_LOADER":str(libs/"proot-loader"),"PROOT_TMP_DIR":str(tmp),"PROOT_NO_SECCOMP":"1",
            "LD_LIBRARY_PATH":str(libs)+":"+str(native_dir),"TMPDIR":str(tmp),"HOME":"/root",
            "PATH":"/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM":"xterm-256color","COLORTERM":"truecolor"})
        args=[str(libs/"libproot.so"),"-0","-r",str(guest),"-b","/dev/null","-b","/dev/zero",
            "-b","/dev/urandom","-b","/dev/random","-b",str(host_workspace)+":/workspace",
            "-w","/workspace","/bin/sh","-i"]
        master,slave=pty.openpty()
        winsize=struct.pack("HHHH",max(2,int(rows)),max(2,int(columns)),0,0)
        fcntl.ioctl(slave,getattr(termios,"TIOCSWINSZ",0x5414),winsize)
        def acquire_controlling_tty():
            # start_new_session creates a session leader; claim the slave PTY as
            # its controlling terminal so interactive shells get job control.
            fcntl.ioctl(0,termios.TIOCSCTTY,0)
            os.tcsetpgrp(0,os.getpgrp())
        process=subprocess.Popen(args,env=env,stdin=slave,stdout=slave,stderr=slave,cwd=str(host_workspace),
            close_fds=True,start_new_session=True,preexec_fn=acquire_controlling_tty,bufsize=0)
        os.close(slave); slave=None
        fcntl.fcntl(master,fcntl.F_SETFL,fcntl.fcntl(master,fcntl.F_GETFL)|os.O_NONBLOCK)
        session_id=uuid.uuid4().hex
        with _TERMINAL_SESSIONS_LOCK:
            _TERMINAL_SESSIONS[session_id]={"master":master,"process":process,"distro":distro}
        master=None
        return _data({"success":True,"session_id":session_id,"executor":"proot","distro":distro,
                      "version":spec["version"],"columns":int(columns),"rows":int(rows)})
    except Exception as exc:
        if process is not None:
            try: process.terminate()
            except Exception: pass
        return _data({"success":False,"error":"Terminal PRoot : "+str(exc)[:240]})
    finally:
        if slave is not None:
            try: os.close(slave)
            except OSError: pass
        if master is not None:
            try: os.close(master)
            except OSError: pass


def terminal_read(session_id, maximum=65536):
    import base64, errno, select
    key=str(session_id or "")
    with _TERMINAL_SESSIONS_LOCK:
        state=_TERMINAL_SESSIONS.get(key)
        if not state: return _data({"success":False,"closed":True,"error":"Session terminal inconnue"})
        data=b""
        try:
            ready,_,_=select.select([state["master"]],[],[],0)
            if ready: data=os.read(state["master"],max(1024,min(int(maximum),65536)))
        except OSError as exc:
            if exc.errno not in (errno.EIO,errno.EBADF,errno.EAGAIN):
                return _data({"success":False,"closed":True,"error":"Lecture PTY interrompue"})
        return _data({"success":True,"output_b64":base64.b64encode(data).decode("ascii"),
                       "closed":state["process"].poll() is not None})


def terminal_write(session_id, data_b64):
    import base64
    try: data=base64.b64decode(str(data_b64 or ""),validate=True)
    except Exception: return _data({"success":False,"error":"Données terminal invalides"})
    if len(data)>65536: return _data({"success":False,"error":"Entrée terminal trop volumineuse"})
    with _TERMINAL_SESSIONS_LOCK:
        state=_TERMINAL_SESSIONS.get(str(session_id or ""))
        if not state: return _data({"success":False,"error":"Session terminal inconnue"})
        try:
            view=memoryview(data)
            while view:
                written=os.write(state["master"],view); view=view[written:]
            return _data({"success":True})
        except OSError as exc: return _data({"success":False,"error":"Écriture PTY impossible: "+str(exc)[:120]})


def terminal_resize(session_id, columns, rows):
    import fcntl, signal, struct, termios
    with _TERMINAL_SESSIONS_LOCK:
        state=_TERMINAL_SESSIONS.get(str(session_id or ""))
        if not state: return _data({"success":False,"error":"Session terminal inconnue"})
        try:
            fcntl.ioctl(state["master"],getattr(termios,"TIOCSWINSZ",0x5414),
                        struct.pack("HHHH",max(2,int(rows)),max(2,int(columns)),0,0))
            try: os.killpg(state["process"].pid,signal.SIGWINCH)
            except OSError: pass
            return _data({"success":True})
        except Exception as exc: return _data({"success":False,"error":"Redimensionnement PTY impossible: "+str(exc)[:120]})


def terminal_close(session_id):
    import signal
    with _TERMINAL_SESSIONS_LOCK: state=_TERMINAL_SESSIONS.pop(str(session_id or ""),None)
    if not state: return _data({"success":True,"closed":True})
    try:
        try: os.killpg(state["process"].pid,signal.SIGTERM)
        except OSError: state["process"].terminate()
        try: state["process"].wait(timeout=2)
        except Exception:
            try: state["process"].kill()
            except Exception: pass
        os.close(state["master"])
        return _data({"success":True,"closed":True})
    except Exception as exc: return _data({"success":False,"error":"Fermeture terminal impossible: "+str(exc)[:120]})

_PREVIEW_PROCESSES = {}
_PREVIEW_LOCK = threading.RLock()


def preview_status(session_id):
    key=str(session_id or "")
    with _PREVIEW_LOCK:
        state=_PREVIEW_PROCESSES.get(key)
        if not state or state["process"].poll() is not None:
            if state: _PREVIEW_PROCESSES.pop(key,None)
            return _data({"success":True,"running":False,"url":""})
        return _data({"success":True,"running":True,"port":state["port"],"url":"http://127.0.0.1:%d"%state["port"],"path":state["path"]})


def preview_stop(session_id):
    import signal
    key=str(session_id or "")
    with _PREVIEW_LOCK: state=_PREVIEW_PROCESSES.pop(key,None)
    if not state: return _data({"success":True,"running":False,"stopped":False})
    process=state["process"]
    try:
        if process.poll() is None:
            try: os.killpg(process.pid,signal.SIGTERM)
            except OSError: process.terminate()
            try: process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                try: os.killpg(process.pid,signal.SIGKILL)
                except OSError: process.kill()
                process.wait(timeout=2)
        return _data({"success":True,"running":False,"stopped":True})
    except Exception as exc: return _data({"success":False,"error":"Arrêt de l’aperçu impossible : "+type(exc).__name__})


def preview_start(session_id,workspace,path,command,port,distro):
    import shlex,socket,signal
    key=str(session_id or "").strip();cmd=str(command or "").strip();distro=str(distro or "").strip().lower()
    try: port=int(port)
    except (TypeError,ValueError): return _data({"success":False,"error":"Port numérique requis"})
    if not key or not cmd or len(cmd)>2000 or any((ord(c)<32 and c not in "\\n\\t") for c in cmd): return _data({"success":False,"error":"Commande d’aperçu invalide"})
    if not 1024<=port<=65535: return _data({"success":False,"error":"Port attendu entre 1 024 et 65 535"})
    if distro not in DISTRIBUTIONS: return _data({"success":False,"error":"L’aperçu local nécessite Alpine ou Debian; SSH distant n’est pas encore relayé dans l’app"})
    try:
        distro,spec,_=_spec(distro);guest=_target_path(distro)
        if not _is_ready(guest,spec["version"]): raise ValueError("Installez d’abord l’environnement Linux")
        if not _toolchain_ready(guest): raise ValueError("Installez les outils Git/Python/Node de l’environnement avant l’aperçu")
        root=Path(workspace).resolve();target=(root/str(path or ".")).resolve()
        if target!=root and root not in target.parents: raise ValueError("Dossier hors workspace interdit")
        if not target.is_dir(): raise ValueError("Dossier de projet introuvable")
        relative=target.relative_to(root).as_posix();guest_work="/workspace" if relative=="." else "/workspace/"+relative
        with _PREVIEW_LOCK:
            existing=_PREVIEW_PROCESSES.get(key)
            if existing and existing["process"].poll() is None and existing["port"]==port and existing["command"]==cmd and existing["path"]==relative:
                return _data({"success":True,"running":True,"port":port,"url":"http://127.0.0.1:%d"%port,"path":relative,"reused":True})
        preview_stop(key)
        with socket.socket() as sock:
            if sock.connect_ex(("127.0.0.1",port))==0: raise ValueError("Port déjà utilisé sur l’appareil; choisissez un autre port")
        data_dir=Path(_FILES_DIR)/"nemesis-linux";libs=data_dir/"libs";tmp=data_dir/"tmp"/("preview-"+distro)
        libs.mkdir(parents=True,exist_ok=True);tmp.mkdir(parents=True,exist_ok=True)
        native=Path(_NATIVE_LIB_DIR)
        for name in ("libproot.so","libproot-loader.so","libtalloc.so","libandroid-shmem.so"):
            source=native/name
            if not source.is_file(): raise RuntimeError("Bibliothèque PRoot absente")
            target_lib=libs/("proot-loader" if name=="libproot-loader.so" else name)
            if not target_lib.exists() or target_lib.stat().st_size!=source.stat().st_size: shutil.copyfile(source,target_lib)
            os.chmod(target_lib,0o700 if name in ("libproot.so","libproot-loader.so") else 0o600)
        if not (libs/"libtalloc.so.2").exists(): os.symlink("libtalloc.so",str(libs/"libtalloc.so.2"))
        resolver=guest/"etc/resolv.conf"
        try: resolver.unlink()
        except FileNotFoundError: pass
        dns=[x.strip() for x in _DNS_SERVERS.split(",") if x.strip()]
        if dns: resolver.write_text("".join("nameserver "+x+"\n" for x in dns),encoding="ascii")
        env=os.environ.copy();env.update({"PROOT_LOADER":str(libs/"proot-loader"),"PROOT_TMP_DIR":str(tmp),"PROOT_NO_SECCOMP":"1","LD_LIBRARY_PATH":str(libs)+":"+str(native),"TMPDIR":str(tmp),"HOME":"/root","PATH":"/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin","HOST":"127.0.0.1","HOSTNAME":"127.0.0.1","PORT":str(port),"VITE_HOST":"127.0.0.1","NEMESIS_PREVIEW_PORT":str(port)})
        shell="export HOST=127.0.0.1 HOSTNAME=127.0.0.1 PORT=%d VITE_HOST=127.0.0.1 NEMESIS_PREVIEW_PORT=%d; cd %s && %s"%(port,port,shlex.quote(guest_work),cmd)
        args=[str(libs/"libproot.so"),"-0","-r",str(guest),"-b","/dev/null","-b","/dev/zero","-b","/dev/urandom","-b","/dev/random","-b","/proc","-b","/sys","-b",str(root)+":/workspace","-w",guest_work,"/bin/sh","-c",shell]
        process=subprocess.Popen(args,env=env,stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,close_fds=True,start_new_session=True)
        deadline=time.monotonic()+45
        ready=False
        while time.monotonic()<deadline:
            if process.poll() is not None: break
            try:
                with socket.create_connection(("127.0.0.1",port),timeout=.4): ready=True;break
            except OSError: time.sleep(.25)
        if not ready:
            try: os.killpg(process.pid,signal.SIGTERM)
            except OSError: process.terminate()
            try: process.wait(timeout=2)
            except Exception: pass
            raise RuntimeError("Serveur non joignable sur localhost après 45 s; vérifiez la commande et le port")
        with _PREVIEW_LOCK: _PREVIEW_PROCESSES[key]={"process":process,"port":port,"path":relative,"command":cmd,"distro":distro}
        return _data({"success":True,"running":True,"port":port,"url":"http://127.0.0.1:%d"%port,"path":relative,"executor":"proot","distro":distro})
    except Exception as exc:
        return _data({"success":False,"error":"Aperçu local : "+str(exc)[:320]})


def configure(files_dir, native_lib_dir, android_abi, dns_servers=""):
    global _FILES_DIR, _NATIVE_LIB_DIR, _ANDROID_ABI, _DNS_SERVERS
    _FILES_DIR = str(files_dir)
    _NATIVE_LIB_DIR = str(native_lib_dir)
    _ANDROID_ABI = str(android_abi)
    _DNS_SERVERS = str(dns_servers)
