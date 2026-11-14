package com.nemesis.apk

import android.content.Context
import android.os.Build
import android.net.ConnectivityManager
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Android-side proof of concept: PRoot + official Alpine minirootfs + real apk install. */
@RunWith(AndroidJUnit4::class)
class ProotSmokeInstrumentedTest {
    @Test fun prootBootsAlpineAndApkInstallsBash() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val scratch = File(context.filesDir, "proot-smoke-poc")
        scratch.deleteRecursively()
        val rootfs = File(scratch, "rootfs")
        val libs = File(scratch, "libs")
        val tmp = File(scratch, "tmp")
        assertTrue("create scratch", tmp.mkdirs())
        var passed = false
        try {
            val nativeDir = File(context.applicationInfo.nativeLibraryDir)
            val proot = File(nativeDir, "libproot.so")
            val loaderFromApk = File(nativeDir, "libproot-loader.so")
            val tallocFromApk = File(nativeDir, "libtalloc.so")
            val shmemFromApk = File(nativeDir, "libandroid-shmem.so")
            assertTrue("PRoot payload extracted from APK: $nativeDir", proot.isFile)
            assertTrue("PRoot static loader extracted from APK", loaderFromApk.isFile)
            assertTrue("libtalloc dependency extracted from APK", tallocFromApk.isFile)
            assertTrue("libandroid-shmem dependency extracted from APK", shmemFromApk.isFile)

            assertTrue("create lib directory", libs.mkdirs())
            val loader = File(libs, "proot-loader")
            loaderFromApk.copyTo(loader, overwrite=true)
            Os.chmod(loader.absolutePath, 0x1c0) // 0700, app-private staged loader
            tallocFromApk.copyTo(File(libs, "libtalloc.so"), overwrite=true)
            shmemFromApk.copyTo(File(libs, "libandroid-shmem.so"), overwrite=true)
            Os.symlink(File(libs, "libtalloc.so").absolutePath, File(libs, "libtalloc.so.2").absolutePath)

            val abi = Build.SUPPORTED_ABIS.first()
            val spec = rootfsSpec(abi)
            val archive = File(scratch, "alpine-minirootfs-$ALPINE_VERSION-${spec.arch}.tar.gz")
            val rootfsUrl = "https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/${spec.arch}/alpine-minirootfs-$ALPINE_VERSION-${spec.arch}.tar.gz"
            val downloadStarted = System.nanoTime()
            val connection = java.net.URL(rootfsUrl).openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 30_000
                useCaches = false
            }
            connection.getInputStream().use { input ->
                archive.outputStream().use { output -> input.copyTo(output) }
            }
            (connection as? java.net.HttpURLConnection)?.disconnect()
            val downloadMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - downloadStarted)
            assertTrue("Runtime download is empty", archive.length() > 0)
            assertEquals("Pinned Alpine rootfs checksum for $abi", spec.sha256, sha256(archive))
            val extractionStarted = System.nanoTime()
            unpackTarGzWithoutRoot(context, archive, rootfs)
            val extractionMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - extractionStarted)
            val unpackedBytes = rootfs.walkTopDown()
                .filter { it.isFile && !it.isSymbolicLinkCompat() }
                .sumOf { it.length() }
            println("ROOTFS_METRICS version=$ALPINE_VERSION abi=$abi arch=${spec.arch} downloadBytes=${archive.length()} downloadMs=$downloadMs unpackedFileBytes=$unpackedBytes extractionMs=$extractionMs")
            val dns = activeDnsServers(context)
            assertTrue("No DNS servers exposed by the active Android network", dns.isNotEmpty())
            val resolver = File(rootfs, "etc/resolv.conf")
            if (resolver.exists() || resolver.isSymbolicLinkCompat()) assertTrue("replace rootfs resolver", resolver.delete())
            resolver.writeText(dns.joinToString(separator="\n", postfix="\n") { "nameserver $it" })

            val environment = mapOf(
                "PROOT_LOADER" to loader.absolutePath,
                "PROOT_TMP_DIR" to tmp.absolutePath,
                "LD_LIBRARY_PATH" to (libs.absolutePath + ":" + nativeDir.absolutePath),
                "TMPDIR" to tmp.absolutePath,
                "HOME" to "/root",
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            )
            val version = runBounded(proot, listOf("-V"), environment, scratch, "version")
            assertEquals("PRoot --version failed: ${version.output}", 0, version.exitCode)
            assertTrue("Unexpected PRoot version output: ${version.output}", version.output.contains("proot", ignoreCase=true))

            val command = "set -eu; echo ROOTFS_OK=$(cat /etc/alpine-release); apk --version; apk update; apk add --no-cache bash python3; bash --version | head -n 1; python3 --version; echo APK_INSTALL_OK"
            val installStarted = System.nanoTime()
            val guest = runBounded(
                proot,
                listOf("-0", "-r", rootfs.absolutePath, "-b", "/dev/null", "-b", "/dev/zero", "-b", "/dev/urandom", "-b", "/dev/random", "-w", "/root", "/bin/sh", "-c", command),
                environment,
                scratch,
                "guest-apk"
            )
            val installMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - installStarted)
            val installedFileBytes = rootfs.walkTopDown()
                .filter { it.isFile && !it.isSymbolicLinkCompat() }
                .sumOf { it.length() }
            println("PACKAGE_METRICS installMs=$installMs installedFileBytes=$installedFileBytes")
            assertEquals("PRoot/Alpine/APK run failed: ${guest.output}", 0, guest.exitCode)
            assertTrue("Alpine rootfs did not run: ${guest.output}", guest.output.contains("ROOTFS_OK=$ALPINE_VERSION"))
            assertTrue("APK package manager did not run: ${guest.output}", guest.output.contains("apk-tools"))
            assertTrue("APK update/install failed in guest: ${guest.output}", guest.output.contains("APK_INSTALL_OK"))

            assertTrue("Installed bash is not callable: ${guest.output}", guest.output.contains("GNU bash"))
            assertTrue("Installed Python is not callable: ${guest.output}", guest.output.contains("Python 3."))

            // Exercise the actual NEMESIS bash tool route, not just a hand-built PRoot command.
            val mobileAgent = Python.getInstance().getModule("mobile_agent")
            mobileAgent.callAttr("configure", context.filesDir.absolutePath, 8090, "", "",
                nativeDir.absolutePath, abi, dns.joinToString(","))
            val managed = JSONObject(mobileAgent.callAttr("environment_install", "alpine").toString())
            assertTrue("Managed Alpine rootfs install failed: $managed", managed.optBoolean("success"))
            val selected = JSONObject(mobileAgent.callAttr("environment_set", "alpine").toString())
            assertTrue("Could not activate Alpine: $selected", selected.optBoolean("success"))
            val routed = JSONObject(mobileAgent.callAttr("_execute_bash",
                "printf 'NEMESIS_PROOT_TOOL_OK '; cat /etc/alpine-release", 30, null).toString())
            assertTrue("NEMESIS bash tool did not run under Alpine: $routed", routed.optBoolean("success"))
            assertEquals("proot", routed.optString("executor"))
            assertEquals("alpine", routed.optString("distro"))
            assertEquals(ALPINE_VERSION, routed.optString("version"))
            assertTrue(routed.optString("output").contains("NEMESIS_PROOT_TOOL_OK $ALPINE_VERSION"))

            // Exercise the real interactive PTY: stdin must be a TTY and read() must stay open.
            val terminal = JSONObject(mobileAgent.callAttr("environment_terminal_start", "alpine", 80, 24).toString())
            assertTrue("Interactive Alpine PTY did not start: ${terminal.optString("error")}", terminal.optBoolean("success"))
            val terminalId = terminal.getString("session_id")
            fun sendTerminal(text: String) {
                val encoded = android.util.Base64.encodeToString(text.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                val result = JSONObject(mobileAgent.callAttr("environment_terminal_write", terminalId, encoded, "alpine").toString())
                assertTrue("PTY write failed: ${result.optString("error")}", result.optBoolean("success"))
            }
            fun collectUntil(marker: String, seed: String, timeoutMs: Long): String {
                val output = StringBuilder(seed)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline && !output.contains(marker)) {
                    val update = JSONObject(mobileAgent.callAttr("environment_terminal_read", terminalId, "alpine").toString())
                    val bytes = android.util.Base64.decode(update.optString("output_b64", ""), android.util.Base64.NO_WRAP)
                    if (bytes.isNotEmpty()) output.append(String(bytes, Charsets.UTF_8))
                    Thread.sleep(40)
                }
                assertTrue("PTY output missing $marker: $output", output.contains(marker))
                return output.toString()
            }
            try {
                sendTerminal("stty -echo\n")
                Thread.sleep(300)
                sendTerminal("stty -a\n")
                val ttyState = collectUntil("-echo", "", 10_000)
                assertTrue("Terminal echo could not be disabled: $ttyState", ttyState.contains("-echo"))
                sendTerminal("test -t 0 && echo NEMESIS_PTY_TTY_OK; case ${'$'}- in *m*) echo NEMESIS_PTY_JOB_CONTROL_OK;; *) echo NEMESIS_PTY_JOB_CONTROL_OFF;; esac; printf NEMESIS_PTY_WAITING=; read value; echo NEMESIS_PTY_INPUT_OK:${'$'}value; echo NEMESIS_PTY_DONE\n")
                val waiting = collectUntil("NEMESIS_PTY_WAITING=", ttyState, 10_000)
                assertTrue("Shell stdin is not a PTY: $waiting", waiting.contains("NEMESIS_PTY_TTY_OK"))
                assertFalse("Shell did not acquire a controlling PTY: $ttyState", ttyState.contains("can't access tty"))
                assertTrue("Interactive job control is unavailable: $waiting", waiting.contains("NEMESIS_PTY_JOB_CONTROL_OK"))
                sendTerminal("interactive-check\n")
                val complete = collectUntil("NEMESIS_PTY_DONE", waiting, 10_000)
                assertTrue("PTY input did not reach the running shell: $complete", complete.contains("NEMESIS_PTY_INPUT_OK:interactive-check"))
                println("NEMESIS_PTY_TEST tty=true interactive_read=true executor=alpine-proot")
            } finally {
                mobileAgent.callAttr("environment_terminal_close", terminalId, "alpine")
            }
            passed = true
        } finally {
            if (passed) scratch.deleteRecursively()
        }
    }

    private data class RootfsSpec(val arch: String, val sha256: String)

    private fun rootfsSpec(abi: String): RootfsSpec = when (abi) {
        "x86" -> RootfsSpec("x86", "8e30123124a53fde96125d4c45818f1c20adfcc32c7de7bfb0e3bc7c1b4d8f6e")
        "x86_64" -> RootfsSpec("x86_64", "2d7f46af9b7e86ad64ebeba3fe9b75b62dd5b259e708afb2ab21268f627696e5")
        "arm64-v8a" -> RootfsSpec("aarch64", "36091efacd083c1cf5a8c837272530bff4fc72db1fbfdef559f52de0d890bca4")
        "armeabi-v7a" -> RootfsSpec("armhf", "1eba584655d74b4705ad2b3260aea0265644b1cc1acee10a0cba1b5d7aa8196b")
        else -> throw IllegalArgumentException("No pinned Alpine rootfs for Android ABI $abi")
    }

    private data class RunResult(val exitCode: Int, val output: String)

    private fun runBounded(
        executable: File,
        args: List<String>,
        environment: Map<String, String>,
        scratch: File,
        label: String
    ): RunResult {
        val outputFile = File(scratch, "$label.log")
        val process = ProcessBuilder(listOf(executable.absolutePath) + args)
            .redirectErrorStream(true)
            .redirectOutput(outputFile)
        process.environment().putAll(environment)
        val child = process.start()
        if (!child.waitFor(180, TimeUnit.SECONDS)) {
            child.destroyForcibly()
            child.waitFor(5, TimeUnit.SECONDS)
            val tail = outputFile.takeIf { it.isFile }?.readText()?.takeLast(12_000).orEmpty()
            fail("Timed out after 180s in $label: $tail")
        }
        return RunResult(child.exitValue(), outputFile.takeIf { it.isFile }?.readText()?.take(128 * 1024).orEmpty())
    }

    private fun activeDnsServers(context: Context): List<String> {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return emptyList()
        return manager.getLinkProperties(network)?.dnsServers.orEmpty()
            .mapNotNull { it.hostAddress?.substringBefore('%') }
            .filter { it.isNotBlank() }
    }

    private fun unpackTarGzWithoutRoot(context: Context, archive: File, destination: File) {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
        val python = Python.getInstance()
        val globals = python.builtins.callAttr("dict")
        val script = """
import os, tarfile, shutil
src = ${pyString(archive.absolutePath)}
dest = ${pyString(destination.absolutePath)}
os.makedirs(dest, exist_ok=True)
root = os.path.realpath(dest)
def safe_path(name):
    while name.startswith('./'): name = name[2:]
    if not name or name == '.': return None
    parts = name.split('/')
    if name.startswith('/') or any(p in ('..', '') for p in parts):
        raise ValueError('unsafe tar path: ' + name)
    path = os.path.join(dest, *parts)
    parent = os.path.dirname(path)
    os.makedirs(parent, exist_ok=True)
    parent_real = os.path.realpath(parent)
    if parent_real != root and not parent_real.startswith(root + os.sep):
        raise ValueError('tar path escapes root: ' + name)
    return path
with tarfile.open(src, 'r:gz') as tf:
    symlinks = []
    directories = []
    hardlinks = []
    for member in tf.getmembers():
        path = safe_path(member.name)
        if path is None: continue
        member.uid = None
        member.gid = None
        if member.isdir():
            os.makedirs(path, exist_ok=True)
            directories.append((path, member.mode & 0o777))
        elif member.isfile():
            stream = tf.extractfile(member)
            if stream is None: raise IOError('missing tar data: ' + member.name)
            with stream, open(path, 'wb') as out: shutil.copyfileobj(stream, out)
            os.chmod(path, member.mode & 0o777)
        elif member.issym():
            symlinks.append((path, member.linkname))
        elif member.islnk():
            hardlinks.append((path, member.linkname))
    for path, target in hardlinks:
        source = safe_path(target)
        if source and os.path.isfile(source): os.link(source, path)
    for path, target in symlinks:
        os.symlink(target, path)
    for path, mode in reversed(directories): os.chmod(path, mode)
""".trimIndent()
        python.builtins.callAttr("exec", script, globals)
    }

    private fun pyString(value: String): String = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun File.isSymbolicLinkCompat(): Boolean = try { Os.lstat(absolutePath).let { (it.st_mode and 0xf000) == 0xa000 } } catch (_: Exception) { false }

    companion object {
        private const val ALPINE_VERSION = "3.22.8"
    }
}
