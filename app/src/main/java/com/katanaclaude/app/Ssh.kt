package com.katanaclaude.app

import com.jcraft.jsch.ChannelExec
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

    fun exec(s: Session, cmd: String, timeoutMs: Long = 90_000): ExecResult {
        val ch = s.openChannel("exec") as ChannelExec
        ch.setCommand(cmd)
        val err = ByteArrayOutputStream()
        ch.setErrStream(err, true)
        val ins = ch.inputStream
        ch.connect(20_000)
        try {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (true) {
                val n = ins.read(buf)
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
            val ch = via.openChannel("exec") as ChannelExec
            ch.setCommand(command)
            ch.setErrStream(LineOutputStream(stderrLine), true)
            ins = ch.inputStream
            outs = ch.outputStream
            ch.connect(if (timeout > 0) timeout else 30_000)
            channel = ch
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
