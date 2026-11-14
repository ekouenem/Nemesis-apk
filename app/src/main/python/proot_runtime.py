"""On-demand, checksummed Linux root filesystems and PRoot execution for NEMESIS."""
import hashlib
import json
import os
import shutil
import subprocess
import tarfile
import ssl
import certifi
import threading
import traceback
import socket
import ipaddress
import urllib.request
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
    try: return (Path(path) / ".nemesis-toolchain-v2").read_text(encoding="ascii").strip() == "2"
    except OSError: return False


def _mark_toolchain_ready(path):
    (Path(path) / ".nemesis-toolchain-v2").write_text("2\n", encoding="ascii")
    marker = Path(path) / ".nemesis-rootfs-ready"
    try:
        version = marker.read_text(encoding="utf-8").strip().split()[0]
        marker.write_text("%s %d\n" % (version, _installed_size(path)), encoding="utf-8")
    except (OSError, IndexError): pass


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
    if entries:
        with (Path(guest)/"etc/hosts").open("a",encoding="ascii") as stream:
            stream.write("\n"+"\n".join(entries)+"\n")
    return entries


def _bootstrap_tools(distro, guest, progress=None):
    """Install a usable toolchain and the app-managed nemesis account in a rootfs."""
    data_dir = Path(_FILES_DIR) / "nemesis-linux"
    native_dir = Path(_NATIVE_LIB_DIR)
    libs = data_dir / "libs"
    tmp = data_dir / "tmp" / ("bootstrap-" + str(distro))
    libs.mkdir(parents=True, exist_ok=True); tmp.mkdir(parents=True, exist_ok=True)
    for name in ("libproot.so", "libproot-loader.so", "libtalloc.so", "libandroid-shmem.so"):
        source = native_dir / name
        if not source.is_file(): raise RuntimeError("Bibliothèque PRoot absente: " + name)
        target = libs / ("proot-loader" if name == "libproot-loader.so" else name)
        if not target.exists() or target.stat().st_size != source.stat().st_size: shutil.copyfile(source, target)
        os.chmod(target, 0o700 if name in ("libproot.so", "libproot-loader.so") else 0o600)
    if not (libs / "libtalloc.so.2").exists(): os.symlink("libtalloc.so", str(libs / "libtalloc.so.2"))
    resolver = guest / "etc/resolv.conf"
    try: resolver.unlink()
    except FileNotFoundError: pass
    dns = [x.strip() for x in _DNS_SERVERS.split(",") if x.strip()]
    if dns: resolver.write_text("".join("nameserver " + x + "\n" for x in dns), encoding="ascii")
    env = os.environ.copy()
    env.update({"PROOT_LOADER": str(libs / "proot-loader"), "PROOT_TMP_DIR": str(tmp), "PROOT_NO_SECCOMP": "1",
                "LD_LIBRARY_PATH": str(libs) + ":" + str(native_dir), "TMPDIR": str(tmp),
                "HOME": "/root", "PATH": "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "HOSTNAME": "nemesis", "DEBIAN_FRONTEND": "noninteractive"})
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
        command = ("apk update && apk add --no-cache bash ca-certificates curl git python3 py3-pip py3-virtualenv nodejs npm openssh-client-default "
                   "sudo shadow coreutils findutils grep sed tar && " + identity + " && rm -rf /var/cache/apk/*")
    elif distro == "debian":
        apt_opts="-o APT::Sandbox::User=root -o Acquire::Retries=3"
        command = ("apt-get " + apt_opts + " update && apt-get " + apt_opts + " install -y --no-install-recommends bash ca-certificates curl git python3 python3-pip python3-venv nodejs npm "
                   "openssh-client sudo passwd zip unzip coreutils findutils grep sed tar && " + identity + " && apt-get " + apt_opts + " clean && rm -rf /var/lib/apt/lists/*")
    else:
        raise ValueError("Distribution non prise en charge")
    if distro == "debian":
        _prime_debian_mirror_hosts(guest)
    if progress: progress("packages",76,"Installation des paquets et configuration de l’utilisateur nemesis…")
    args = [str(libs / "libproot.so"), "-0", "-r", str(guest),
            "-b", "/dev/null", "-b", "/dev/zero", "-b", "/dev/urandom", "-b", "/dev/random",
            "-b", "/proc", "-b", "/sys", "-w", "/root", "/bin/sh", "-c", command]
    completed = subprocess.run(args, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               timeout=1800, check=False)
    output = completed.stdout.decode("utf-8", "replace")
    if completed.returncode != 0:
        tail = output[-4000:]
        raise RuntimeError("Installation/configuration des paquets échouée (code %d):\n%s" % (completed.returncode, tail))
    verify_command=("command -v git python3 node npm sudo >/dev/null && "
                    "grep -qx nemesis /etc/hostname && id nemesis && "
                    "printf 'nemesis123\\n' | su -s /bin/sh -c 'sudo -S -p NEMESIS_PROMPT id -un' nemesis")
    verify = subprocess.run(args[:-3] + ["/bin/sh", "-c", verify_command], env=env,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60, check=False)
    if verify.returncode != 0:
        raise RuntimeError("Vérification post-installation (outils/hostname/compte/sudo) échouée (code %d):\n%s" %
                           (verify.returncode, verify.stdout.decode("utf-8", "replace")[-2500:]))
    if progress: progress("verify",96,"Outils, hostname nemesis et accès sudo vérifiés.")


def install(distro):
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
        if target.exists() or target.is_symlink():
            raise ValueError("Installation existante incomplète; fichiers conservés. Supprimez cette distribution puis réessayez.")
        url, filename = _archive_url(distro, spec, arch)
        partial = base / (filename + ".part")
        staging = base / (spec["root"] + "-" + arch + ".installing")
        archive_bytes = 0
        request = urllib.request.Request(url, headers={"User-Agent": "NEMESIS-Android/PRoot", "Accept-Encoding":"identity"})
        with _ROOTFS_LOCK:
            if _is_ready(target, spec["version"]):
                _set_install_progress(distro,"complete",100,"Environnement déjà installé et vérifié.")
                return status(distro)
            if staging.exists(): shutil.rmtree(staging)
            try:
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
                if actual_hash!=checksum: raise ValueError("Vérification SHA-256 échouée; archive téléchargée refusée.")
                def extraction_progress(done,total):
                    percent=54+int(19*done/max(1,total))
                    _set_install_progress(distro,"extract",percent,"Extraction sécurisée : %d / %d entrées"%(done,total))
                _set_install_progress(distro,"extract",53,"Extraction sécurisée du rootfs…")
                _extract_archive(partial,staging,spec["strip"],spec["mode"],extraction_progress)
                _bootstrap_tools(distro,staging,lambda phase,p,detail:_set_install_progress(distro,phase,p,detail))
                shell=staging/"bin/sh"
                if not (shell.is_file() or shell.is_symlink()) or not (staging/"etc").is_dir():
                    raise ValueError("Rootfs invalide après extraction : /bin/sh ou /etc absent.")
                actual_size=_installed_size(staging)
                (staging/".nemesis-rootfs-ready").write_text("%s %d\n"%(spec["version"],actual_size),encoding="utf-8")
                _set_install_progress(distro,"finalize",98,"Vérification finale et activation…")
                os.replace(staging,target)
            finally:
                for path in (partial,staging):
                    try:
                        if path.is_dir() and not path.is_symlink(): shutil.rmtree(path)
                        else: path.unlink()
                    except FileNotFoundError: pass
        result=json.loads(status(distro))
        result.update({"installed":True,"downloaded_bytes":archive_bytes,
                       "actual_installed_bytes":_installed_size_cached(target,spec["version"])})
        _set_install_progress(distro,"complete",100,"Installation et vérification terminées.")
        return _data(result)
    except Exception as exc:
        trace=traceback.format_exc()
        message=(str(exc)+"\n\nDiagnostic complet :\n"+trace[-7000:])[:9000]
        _set_install_progress(distro,"error",100,"Échec : "+str(exc)[:250],error=message)
        return _data({"success":False,"installed":False,"error":message})


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
