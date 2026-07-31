package com.example.offlinellm.data.local

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Best-effort root helpers for the experimental [exp/root-mode] build.
 *
 * Root does **not** enable NPU/Vulkan. It is used to:
 * - detect su
 * - probe vendor libs / DSP nodes
 * - optionally read GGUF via real filesystem paths (skip SAF materialize)
 * - chmod a+r on model files so the app process can mmap them
 */
object RootShell {
    data class ExecResult(
        val code: Int,
        val stdout: String,
        val stderr: String,
    ) {
        val ok: Boolean get() = code == 0
        val out: String get() = stdout.trim()
    }

    @Volatile private var suCached: Boolean? = null
    @Volatile private var rootGranted: Boolean? = null

    fun clearCache() {
        suCached = null
        rootGranted = null
    }

    fun isSuPresent(): Boolean {
        suCached?.let { return it }
        val found = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/vendor/bin/su",
            "/data/local/tmp/su",
        ).any { File(it).exists() } || which("su") != null
        suCached = found
        return found
    }

    fun which(bin: String): String? {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "which $bin 2>/dev/null"))
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor(2, TimeUnit.SECONDS)
            out.lineSequence().firstOrNull { it.startsWith("/") }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Ask Magisk/superuser for a shell. May show a root prompt.
     * Result is cached for the process lifetime unless [clearCache].
     */
    fun ensureRoot(timeoutSec: Long = 45): Boolean {
        rootGranted?.let { return it }
        if (!isSuPresent()) {
            rootGranted = false
            return false
        }
        val r = su("id", timeoutSec = timeoutSec)
        val ok = r.ok && r.stdout.contains("uid=0")
        rootGranted = ok
        AppLogger.d("Root", "ensureRoot ok=$ok out=${r.stdout.take(120)} err=${r.stderr.take(80)}")
        return ok
    }

    fun isRootGranted(): Boolean = rootGranted == true

    fun su(cmd: String, timeoutSec: Long = 20): ExecResult {
        return try {
            val pb = ProcessBuilder("su", "-c", cmd)
            pb.redirectErrorStream(false)
            val p = pb.start()
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val tOut = Thread {
                try {
                    BufferedReader(InputStreamReader(p.inputStream)).use { br ->
                        var line: String?
                        while (br.readLine().also { line = it } != null) {
                            stdout.append(line).append('\n')
                        }
                    }
                } catch (_: Throwable) {
                }
            }
            val tErr = Thread {
                try {
                    BufferedReader(InputStreamReader(p.errorStream)).use { br ->
                        var line: String?
                        while (br.readLine().also { line = it } != null) {
                            stderr.append(line).append('\n')
                        }
                    }
                } catch (_: Throwable) {
                }
            }
            tOut.start(); tErr.start()
            val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                p.destroyForcibly()
                return ExecResult(-1, stdout.toString(), "timeout after ${timeoutSec}s\n" + stderr)
            }
            tOut.join(1000); tErr.join(1000)
            ExecResult(p.exitValue(), stdout.toString(), stderr.toString())
        } catch (t: Throwable) {
            ExecResult(-1, "", t.message ?: t.javaClass.simpleName)
        }
    }

    /** True if the **app process** can open the path (what llama mmap needs). */
    fun appCanRead(path: String): Boolean {
        return try {
            val f = File(path)
            f.isFile && f.canRead() && f.length() > 0L
        } catch (_: Throwable) {
            false
        }
    }

    fun tryChmodWorldReadable(path: String): Boolean {
        if (!ensureRoot()) return false
        val r = su("chmod a+r " + shellQuote(path) + " && ls -l " + shellQuote(path))
        AppLogger.d("Root", "chmod a+r $path -> ${r.code} ${r.out.take(100)}")
        return appCanRead(path)
    }

    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    fun listGguf(dir: String): List<Pair<String, Long>> {
        // Prefer Java list when readable
        try {
            val d = File(dir)
            if (d.isDirectory && d.canRead()) {
                return d.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".gguf", true) }
                    ?.map { it.name to it.length() }
                    ?: emptyList()
            }
        } catch (_: Throwable) {
        }
        if (!ensureRoot()) return emptyList()
        val r = su("ls -1 " + shellQuote(dir) + " 2>/dev/null")
        if (!r.ok) return emptyList()
        val out = mutableListOf<Pair<String, Long>>()
        for (name in r.stdout.lineSequence()) {
            val n = name.trim()
            if (!n.endsWith(".gguf", true)) continue
            val full = dir.trimEnd('/') + "/" + n
            val sz = su("stat -c%s " + shellQuote(full) + " 2>/dev/null || wc -c < " + shellQuote(full))
            val len = sz.out.lineSequence().firstOrNull()?.trim()?.toLongOrNull() ?: 0L
            out.add(n to len)
        }
        return out
    }
}
