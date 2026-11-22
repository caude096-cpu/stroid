package app.stroid

import android.content.Context
import android.system.Os
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

class TerminalSession(
    private val ctx: Context,
    private val projects: File,
    private val emit: (ByteArray) -> Unit,
    private val onEnd: () -> Unit
) {
    private val rootfs = File(ctx.filesDir, "rootfs")
    private val marker = File(ctx.filesDir, "rootfs.installed")
    private var proc: Process? = null
    private var out: OutputStream? = null
    private val pending = ArrayList<ByteArray>()

    @Volatile
    var alive = false
        private set

    private fun say(s: String) = emit(s.replace("\n", "\r\n").toByteArray())

    fun start(cols: Int, rows: Int) {
        alive = true
        thread(name = "stroid-terminal") {
            try {
                ensureRootfs()
                launch(cols, rows)
            } catch (e: Throwable) {
                say("\n[error] ${e.message ?: e.javaClass.simpleName}\n")
            } finally {
                synchronized(this) { out = null }
                alive = false
                say("\n[session ended - type anything to restart]\n")
                onEnd()
            }
        }
    }

    @Synchronized
    fun write(b: ByteArray) {
        val o = out
        if (o == null) {
            if (pending.size < 1000) pending.add(b)
            return
        }
        try {
            o.write(b)
            o.flush()
        } catch (_: Exception) {
        }
    }

    @Synchronized
    private fun attach(o: OutputStream) {
        out = o
        try {
            for (b in pending) o.write(b)
            o.flush()
        } catch (_: Exception) {
        }
        pending.clear()
    }

    fun stop() {
        proc?.destroyForcibly()
    }

    private class Counting(i: InputStream, val onBytes: (Long) -> Unit) : FilterInputStream(i) {
        var n = 0L
        override fun read(): Int {
            val r = super.read()
            if (r >= 0) { n++; onBytes(n) }
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val r = super.read(b, off, len)
            if (r > 0) { n += r; onBytes(n) }
            return r
        }
    }

    private fun ensureRootfs() {
        if (marker.exists() && File(rootfs, "usr/bin/env").exists()) return
        marker.delete()
        say("Setting up the Linux environment (first run only)...\n")
        rootfs.deleteRecursively()
        rootfs.mkdirs()

        val total = runCatching {
            ctx.assets.openFd("rootfs.tar.gz").use { it.length }
        }.getOrDefault(-1L)

        val tar = ProcessBuilder("/system/bin/tar", "-xf", "-", "-C", rootfs.absolutePath)
            .redirectErrorStream(true).start()
        val drain = thread(isDaemon = true) {
            try {
                val buf = ByteArray(8192)
                while (tar.inputStream.read(buf) >= 0) { }
            } catch (_: Exception) {
            }
        }

        var lastPct = -1
        val counting = Counting(BufferedInputStream(ctx.assets.open("rootfs.tar.gz"), 1 shl 16)) { n ->
            if (total > 0) {
                val pct = (n * 100 / total).toInt()
                if (pct != lastPct && pct % 2 == 0) {
                    lastPct = pct
                    emit("\r  Unpacking... $pct%".toByteArray())
                }
            }
        }
        try {
            counting.use { src ->
                GZIPInputStream(src, 1 shl 16).use { gz ->
                    tar.outputStream.use { o -> gz.copyTo(o, 1 shl 16) }
                }
            }
        } catch (e: Exception) {
            say("\n[warn] unpack: ${e.message}\n")
        }
        tar.waitFor()
        drain.join(3000)

        if (!File(rootfs, "usr/bin/env").exists()) {
            throw IllegalStateException("Unpack failed (rootfs incomplete)")
        }

        File(rootfs, "etc/resolv.conf").apply {
            delete(); parentFile?.mkdirs()
            writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        }
        File(rootfs, "etc/hosts").apply {
            delete(); writeText("127.0.0.1 localhost\n::1 localhost\n")
        }
        File(rootfs, "root/projects").mkdirs()
        File(rootfs, "tmp").mkdirs()
        marker.writeText("1")
        say("\n\nDone.\n")
    }

    private fun launch(cols: Int, rows: Int) {
        val nlib = ctx.applicationInfo.nativeLibraryDir
        val libDir = File(ctx.filesDir, "lib").apply { deleteRecursively(); mkdirs() }
        runCatching { ctx.assets.open("libs.txt").bufferedReader().readLines() }
            .getOrDefault(emptyList()).forEach { line ->
                val p = line.trim().split(" ")
                if (p.size == 2) runCatching {
                    Os.symlink(File(nlib, p[0]).path, File(libDir, p[1]).path)
                }
            }
        val tmp = File(ctx.filesDir, "tmp").apply { mkdirs() }

        val proot = File(nlib, "libproot.so")
        if (!proot.exists()) throw IllegalStateException("proot is missing from the app")

        val start = "stty cols $cols rows $rows 2>/dev/null; exec bash -l"
        val cmd = listOf(
            proot.path, "--link2symlink", "-0", "--kill-on-exit",
            "-r", rootfs.path,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "${projects.path}:/root/projects",
            "-w", "/root/projects",
            "/usr/bin/env", "-i",
            "HOME=/root", "TERM=xterm-256color", "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "/usr/bin/script", "-qf", "-c", start, "/dev/null"
        )
        val pb = ProcessBuilder(cmd)
        pb.directory(ctx.filesDir)
        pb.redirectErrorStream(true)
        pb.environment().apply {
            clear()
            put("PROOT_TMP_DIR", tmp.path)
            put("PROOT_LOADER", File(nlib, "libproot-loader.so").path)
            put("LD_LIBRARY_PATH", libDir.path)
            put("PROOT_NO_SECCOMP", "1")
        }
        val p = pb.start()
        proc = p
        attach(p.outputStream)

        val buf = ByteArray(4096)
        val ins = p.inputStream
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            emit(buf.copyOf(n))
        }
        p.waitFor()
    }
}
