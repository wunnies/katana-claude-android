package com.katanaclaude.app

import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/** JSch plumbing with no Android dependencies, so it can be tested on a plain JVM. */
object Ssh {

    data class ExecResult(val code: Int, val out: String, val err: String)

    /** How long to wait for the server to confirm a new channel. OpenSSH's own client waits forever. */
    const val CHANNEL_TIMEOUT_MS = 120_000

    /** Optional progress logger for slow channel opens (set by the app). */
    @Volatile var slowLog: ((String) -> Unit)? = null

    /** A channel (exec/proxy/terminal) the server refused or never confirmed, with the step and the reason. */
    class ChannelOpenException(msg: String) : IOException(msg)

    private fun reasonName(code: Int) = when (code) {
        1 -> "administratively prohibited"
        2 -> "connect failed"
        3 -> "unknown channel type"
        4 -> "resource shortage, e.g. too many sessions"
        else -> "reason code $code"
    }

    /**
     * Opens and connects a channel, retrying up to 3 times. JSch only says "channel is not opened",
     * so this reports which step it was and whether the server refused (with its reason code) or never answered.
     * [prepare] runs on each fresh channel before connect (set the command, grab streams).
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Channel> connectChannel(
        s: Session, type: String, what: String, timeoutMs: Int = CHANNEL_TIMEOUT_MS,
        attempts: Int = 2, prepare: (T) -> Unit,
    ): T {
        var last = ""
        for (attempt in 1..attempts) {
            if (!s.isConnected) throw IOException("SSH connection closed before $what")
            val ch = s.openChannel(type) as T
            prepare(ch)
            // HPC login nodes can take a long time to set up a session (PAM, home mounts, quota/MOTD
            // scripts), and OpenSSH's client simply waits. Log while waiting so it's clear what's happening.
            val watcher = slowLog?.let { log ->
                kotlin.concurrent.thread(isDaemon = true, name = "channel-wait") {
                    try {
                        var t = 0
                        while (true) {
                            Thread.sleep(10_000); t += 10
                            log("  …waiting for the server to open $what (${t}s)")
                        }
                    } catch (_: InterruptedException) {
                    }
                }
            }
            try {
                ch.connect(timeoutMs)
                return ch
            } catch (e: JSchException) {
                if (e.message?.contains("channel is not opened") != true) throw e
                val code = ch.exitStatus
                last = if (code > 0) "the server refused it (${reasonName(code)})" else "no answer within ${timeoutMs / 1000}s"
                try { ch.disconnect() } catch (_: Exception) {}
                if (attempt < attempts) Thread.sleep(2000L * attempt)
            } finally {
                watcher?.interrupt()
            }
        }
        throw ChannelOpenException("Couldn't open an SSH channel for $what: $last (tried $attempts times)")
    }

    private fun newJSch(keyFile: File): JSch = JSch().apply { addIdentity(keyFile.absolutePath) }

    /**
     * `ssh katana`: public-key auth, known_hosts with trust on first use, keepalives every 15 s
     * (so a dead network is noticed within about a minute).
     */
    fun openLogin(keyFile: File, knownHosts: File, user: String, host: String, port: Int, log: (String) -> Unit): Session {
        val jsch = newJSch(keyFile)
        jsch.setKnownHosts(knownHosts.absolutePath)
        val s = jsch.getSession(user, host, port)
        s.setConfig("StrictHostKeyChecking", "ask")
        s.setConfig("PreferredAuthentications", "publickey")
        s.userInfo = TofuUserInfo(log)
        s.setServerAliveInterval(15_000)
        s.setServerAliveCountMax(4)
        s.connect(30_000)
        return s
    }

    /**
     * `ssh katana-job`: the transport is `proxyCommand` run on the katana connection, which is
     * what `ProxyCommand ssh katana "~/resolve_node.sh"` does. Host key checking is off, as in your ssh config.
     */
    fun openJob(keyFile: File, user: String, via: Session, proxyCommand: String, onStderr: (String) -> Unit): Session {
        val jsch = newJSch(keyFile)
        val s = jsch.getSession(user, "katana-job", 22)
        s.setConfig("StrictHostKeyChecking", "no")
        s.setConfig("PreferredAuthentications", "publickey")
        s.setProxy(ExecChannelProxy(via, proxyCommand, onStderr))
        // No timeout here: resolve_node.sh itself waits (up to 10 min) for sshd on the node.
        s.connect()
        return s
    }

    fun exec(s: Session, cmd: String, timeoutMs: Long = 90_000, what: String = "a command"): ExecResult {
        var err = ByteArrayOutputStream()
        var ins: InputStream? = null
        val ch = connectChannel<ChannelExec>(s, "exec", what) {
            it.setCommand(cmd)
            err = ByteArrayOutputStream()
            it.setErrStream(err, true)
            ins = it.inputStream
        }
        val input = ins!!
        try {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (System.nanoTime() > deadline) throw IOException("command timed out: $cmd")
            }
            var waited = 0
            while (!ch.isClosed && waited < 50) {
                Thread.sleep(100)
                waited++
            }
            return ExecResult(ch.exitStatus, String(out.toByteArray(), Charsets.UTF_8), String(err.toByteArray(), Charsets.UTF_8))
        } finally {
            ch.disconnect()
        }
    }

    /** Pipes an SSH session over an exec channel on another session, the same as OpenSSH's ProxyCommand. */
    class ExecChannelProxy(
        private val via: Session,
        private val command: String,
        private val stderrLine: (String) -> Unit,
    ) : Proxy {
        private var channel: ChannelExec? = null
        private var ins: InputStream? = null
        private var outs: OutputStream? = null

        override fun connect(socket_factory: SocketFactory?, host: String?, port: Int, timeout: Int) {
            channel = connectChannel<ChannelExec>(via, "exec", "the ProxyCommand ($command) on katana") {
                it.setCommand(command)
                it.setErrStream(LineOutputStream(stderrLine), true)
                ins = it.inputStream
                outs = it.outputStream
            }
        }

        override fun getInputStream(): InputStream? = ins
        override fun getOutputStream(): OutputStream? = outs
        override fun getSocket(): Socket? = null
        override fun close() {
            try { channel?.disconnect() } catch (_: Exception) {}
        }
    }

    /** Splits a byte stream (e.g. resolve_node.sh's stderr) into lines. */
    class LineOutputStream(private val onLine: (String) -> Unit) : OutputStream() {
        private val buf = ByteArrayOutputStream()
        override fun write(b: Int) {
            if (b == '\n'.code) flushLine() else buf.write(b)
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            for (i in off until off + len) write(b[i].toInt())
        }
        private fun flushLine() {
            val s = String(buf.toByteArray(), Charsets.UTF_8).trimEnd('\r')
            buf.reset()
            if (s.isNotBlank()) onLine(s)
        }
        override fun close() = flushLine()
    }

    /** Trust-on-first-use for katana's host key. A changed key is refused. */
    open class TofuUserInfo(private val log: (String) -> Unit) : UserInfo {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String? = null
        override fun promptPassword(message: String?): Boolean = false
        override fun promptPassphrase(message: String?): Boolean = false
        override fun promptYesNo(message: String?): Boolean {
            val m = message ?: return false
            if (m.contains("HAS CHANGED")) {
                log("⚠ " + m.lineSequence().take(6).joinToString(" "))
                return false
            }
            if (m.contains("authenticity of host")) {
                log("First connection: trusting host key. " + m.lineSequence().drop(1).firstOrNull().orEmpty())
                return true
            }
            if (m.contains("does not exist")) return true
            return false
        }
        override fun showMessage(message: String?) {
            if (!message.isNullOrBlank()) log(message.trim())
        }
    }
}
