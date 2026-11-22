package com.katanaclaude.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Process-wide owner of the SSH connections. It lives as long as the process, and
 * [TunnelService] keeps the process alive in the foreground. The activity only attaches to it.
 *
 * Flow: ssh katana -> qstat (reuse an existing job) or qsub -> poll qstat until R ->
 *       ssh katana-job (ProxyCommand: ~/resolve_node.sh over the katana connection) ->
 *       interactive shell -> tmux new -A -s claude 'claude --resume'
 */
object TunnelManager {

    enum class State(val label: String, val active: Boolean) {
        IDLE("Idle", false),
        CONNECTING("Connecting to katana", true),
        SUBMITTING("Submitting job", true),
        WAITING("Waiting for job", true),
        TUNNELLING("Tunnelling to job", true),
        CONNECTED("Connected", true),
        RECONNECTING("Reconnecting", true),
        ERROR("Error", false),
    }

    interface Listener {
        fun onLog(line: String) {}
        fun onState(state: State, detail: String) {}
        fun onTerminalData(data: ByteArray) {}
        fun onTerminalReset() {}
    }

    private class Stopped : Exception("stopped")
    private class Fatal(msg: String) : Exception(msg)
    private enum class Outcome { USER_STOP, REMOTE_EXIT, DROPPED }

    @Volatile var state = State.IDLE; private set
    @Volatile var detail = ""; private set
    @Volatile var jobId: String? = null; private set
    @Volatile var jobNode: String? = null; private set

    @Volatile var cols = 80
    @Volatile var rows = 24

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val logLines = ArrayDeque<String>()
    private val ring = ByteRing(256 * 1024)
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "ssh-writer") }
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private lateinit var app: Context

    /** One press of Start. Each run owns its sessions, so a stale run can never touch a newer one. */
    private class Run {
        @Volatile var stopped = false
        @Volatile var thread: Thread? = null
        @Volatile var login: Session? = null
        @Volatile var job: Session? = null
        @Volatile var shell: ChannelExec? = null
        @Volatile var shellOut: OutputStream? = null

        fun closeAll() {
            val sh = shell
            val j = job
            val l = login
            shell = null
            shellOut = null
            job = null
            login = null
            try { sh?.disconnect() } catch (_: Exception) {}
            try { j?.disconnect() } catch (_: Exception) {}
            try { l?.disconnect() } catch (_: Exception) {}
        }
    }

    @Volatile private var run: Run? = null

    // ---------------------------------------------------------------- listeners & log

    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)

    fun logSnapshot(): List<String> = synchronized(logLines) { logLines.toList() }
    fun terminalSnapshot(): ByteArray = ring.snapshot()

    fun log(msg: String) {
        val line = "[${timeFmt.format(Date())}] $msg"
        synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > 3000) logLines.removeFirst()
        }
        main.post { listeners.forEach { it.onLog(line) } }
    }

    private fun setState(s: State, d: String = s.label) {
        state = s
        detail = d
        main.post { listeners.forEach { it.onState(s, d) } }
    }

    // ---------------------------------------------------------------- public API

    fun init(ctx: Context) {
        if (!::app.isInitialized) app = ctx.applicationContext
        if (Ssh.slowLog == null) Ssh.slowLog = { log(it) }
    }

    fun isRunning(): Boolean = run?.let { !it.stopped && it.thread?.isAlive == true } ?: false

    /** The one button: does the whole flow, reusing a job and tmux session if they exist. */
    fun start(ctx: Context) {
        init(ctx)
        synchronized(this) {
            if (isRunning()) {
                log("Already running.")
                return
            }
            Prefs(app).wantConnected = true
            val r = Run()
            run = r
            r.thread = thread(name = "tunnel-worker") { workerMain(r) }
        }
        TunnelService.start(app)
    }

    /** Closes the SSH connections. The PBS job and tmux (and claude) keep running on katana. */
    fun disconnect(reason: String = "Disconnected. The job and tmux session are still running on katana.") {
        if (::app.isInitialized) Prefs(app).wantConnected = false
        val r = run
        if (r != null) {
            r.stopped = true
            r.thread?.interrupt()
            r.closeAll()
        }
        log(reason)
        setState(State.IDLE, "Disconnected")
        if (::app.isInitialized) TunnelService.stop(app)
    }

    /** Disconnects, then runs qdel on the tunnel job(s), which ends tmux and claude too. */
    fun endJob(ctx: Context) {
        init(ctx)
        val known = jobId
        disconnect("Disconnecting to end the job…")
        thread(name = "qdel") {
            val p = Prefs(app)
            var s: Session? = null
            try {
                log("▶ ssh ${p.user}@${p.host} to run qdel")
                s = openLogin(p)
                val jobs = Pbs.parseQstat(exec(s, Pbs.bashLc("qstat")).out, p.jobName).filter { it.state in Pbs.ALIVE }
                val ids = (jobs.map { it.id } + listOfNotNull(known)).map { Pbs.shortId(it) }.distinct()
                if (ids.isEmpty()) {
                    log("No ${p.jobName} job found, so there's nothing to end.")
                } else {
                    val r = exec(s, Pbs.bashLc("qdel " + ids.joinToString(" ") { Pbs.shq(it) }))
                    log(if (r.code == 0) "✔ qdel ${ids.joinToString(" ")}" else "qdel exit ${r.code}: ${r.err.trim()} ${r.out.trim()}")
                }
                jobId = null
            } catch (e: Exception) {
                log("❌ qdel failed: ${describe(e)}")
            } finally {
                s?.disconnect()
            }
        }
    }

    fun sendInput(bytes: ByteArray) {
        val out = run?.shellOut ?: return
        writer.execute {
            try {
                out.write(bytes)
                out.flush()
            } catch (_: Exception) {
            }
        }
    }

    fun resize(c: Int, r: Int) {
        if (c < 10 || r < 3) return
        val changed = c != cols || r != rows
        cols = c
        rows = r
        val ch = run?.shell ?: return
        if (!changed) return
        writer.execute {
            try {
                ch.setPtySize(c, r, 0, 0)
            } catch (_: Exception) {
            }
        }
    }

    /** Briefly changes the pty size, so tmux/claude repaint the whole screen (used after reopening). */
    fun forceRedraw() {
        val ch = run?.shell ?: return
        writer.execute {
            try {
                ch.setPtySize(cols, maxOf(3, rows - 1), 0, 0)
                Thread.sleep(120)
                ch.setPtySize(cols, rows, 0, 0)
            } catch (_: Exception) {
            }
        }
    }

    // ---------------------------------------------------------------- worker

    private fun workerMain(r: Run) {
        val p = Prefs(app)
        var attempt = 0
        var everConnected = false
        var jobKnown = false
        try {
            while (!r.stopped) {
                try {
                    val outcome = runOnce(r, p, onJobKnown = { jobKnown = true }, onConnected = {
                        everConnected = true
                        attempt = 0
                    })
                    when (outcome) {
                        Outcome.USER_STOP -> return
                        Outcome.REMOTE_EXIT -> {
                            log("Remote shell exited. Press Start to reconnect (tmux keeps claude running).")
                            Prefs(app).wantConnected = false
                            setState(State.IDLE, "Shell exited")
                            return
                        }
                        Outcome.DROPPED -> log("⚠ Connection lost.")
                    }
                } catch (e: Stopped) {
                    return
                } catch (e: Fatal) {
                    if (r.stopped) return
                    log("❌ ${e.message}")
                    Prefs(app).wantConnected = false
                    setState(State.ERROR, e.message ?: "Error")
                    return
                } catch (e: Exception) {
                    if (r.stopped) return
                    log("❌ ${describe(e)}")
                    val retryable = (everConnected || jobKnown) && p.autoReconnect && !isAuthOrHostKeyError(e)
                    if (!retryable) {
                        Prefs(app).wantConnected = false
                        setState(State.ERROR, describe(e))
                        return
                    }
                }
                r.closeAll()
                if (r.stopped) return
                attempt++
                if (attempt > 30) {
                    log("❌ Gave up after 30 reconnect attempts.")
                    Prefs(app).wantConnected = false
                    setState(State.ERROR, "Gave up reconnecting")
                    return
                }
                val delay = minOf(60, 3 + attempt * 3)
                setState(State.RECONNECTING, "Retrying in ${delay}s (attempt $attempt)")
                log("Retrying in ${delay}s (attempt $attempt). Is the VPN still connected?")
                sleepOrStop(r, delay * 1000L)
            }
        } catch (_: Stopped) {
        } finally {
            r.closeAll()
            val stillCurrent = synchronized(this) { run === r }
            if (stillCurrent) {
                if (!r.stopped && state != State.ERROR && state != State.IDLE) setState(State.IDLE)
                TunnelService.stop(app)
            }
        }
    }

    private fun runOnce(r: Run, p: Prefs, onJobKnown: () -> Unit, onConnected: () -> Unit): Outcome {
        r.closeAll()
        jobNode = null
        if (!SshKeys.hasKey(app)) throw Fatal("No SSH key yet. Open ⋮ → Settings & SSH key and generate one first.")

        // Step 2: ssh katana
        setState(State.CONNECTING, "ssh ${p.user}@${p.host}")
        log("▶ ssh ${p.user}@${p.host}:${p.port}")
        val l = openLogin(p)
        r.login = l
        log("✔ Logged in to katana (${l.serverVersion})")
        checkStop(r)

        // Reuse a tunnel-sshd job that is already queued or running, so there's never a duplicate.
        log("▶ qstat (looking for an existing ${p.jobName} job)")
        val existing = Pbs.bestAlive(Pbs.parseQstat(exec(l, Pbs.bashLc("qstat"), what = "qstat on katana").out, p.jobName))
        if (existing != null) {
            jobId = existing.id
            log("Found existing ${p.jobName} job ${existing.id} (${Pbs.describe(existing.state)}), so I'm reusing it instead of submitting a new one.")
        } else {
            setState(State.SUBMITTING, "qsub ${p.jobScript}")
            log("▶ qsub ${p.jobScript}")
            val sub = exec(l, Pbs.bashLc(Pbs.submitScript(p.jobScript)), what = "qsub on katana")
            sub.err.lineSequence().filter { it.isNotBlank() }.forEach { log("  $it") }
            val id = Pbs.parseQsubId(sub.out)
                ?: throw Fatal("qsub failed (exit ${sub.code}): ${(sub.err + " " + sub.out).trim().take(400)}")
            jobId = id
            log("✔ Submitted job $id")
        }
        onJobKnown()

        // Step 3: poll qstat until the job is R
        val id = jobId!!
        val t0 = SystemClock.elapsedRealtime()
        var lastState = ""
        var lastComment: String? = null
        while (true) {
            checkStop(r)
            val q = exec(l, Pbs.bashLc(Pbs.qstatScript(id)), what = "qstat on katana")
            val (table, extra) = q.out.split("@@", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val j = Pbs.findById(Pbs.parseQstat(table, p.jobName), id)
            val el = elapsed(t0)
            if (j == null || j.state !in Pbs.ALIVE) {
                jobId = null
                throw Fatal(
                    "Job $id ended before the tunnel opened (${j?.let { Pbs.describe(it.state) } ?: "no longer in qstat"}). " +
                        "Check ~/sshd_debug/run.log and the tunnel-sshd.o/.e files on katana."
                )
            }
            if (j.state != lastState) {
                log("[$el] job ${j.id}: ${j.state} (${Pbs.describe(j.state)})")
                lastState = j.state
            } else {
                log("[$el] qstat: ${j.state}")
            }
            val comment = Pbs.parseComment(extra)
            if (comment != null && comment != lastComment) {
                log("  PBS: $comment")
                lastComment = comment
            }
            setState(State.WAITING, "Job ${Pbs.shortId(j.id)} ${Pbs.describe(j.state)} · $el")
            if (j.state == "R") break
            sleepOrStop(r, p.pollSeconds * 1000L)
        }

        // Step 4: leave the katana shell and ssh to katana-job through the ProxyCommand
        log("✔ Job is running. Leaving the katana shell and running ssh katana-job (ProxyCommand ${p.proxyCommand})")
        setState(State.TUNNELLING, "ssh katana-job")
        val js = openJob(p, l)
        r.job = js
        log("✔ Connected to katana-job (${js.serverVersion})")
        checkStop(r)

        // Step 5: a terminal on the job node running claude --resume (inside tmux)
        log("▶ Starting the terminal on the job node")
        var stream: InputStream? = null
        val ch = Ssh.connectChannel<ChannelExec>(js, "exec", "the terminal on the job node") {
            it.setPty(true)
            it.setPtyType("xterm-256color", cols, rows, 0, 0)
            it.setCommand(Pbs.remoteCommand(Pbs.launchScript(p.claudeCommand, p.useTmux, p.tmuxSession, p.tmuxMouse)))
            stream = it.inputStream
        }
        val input = stream!!
        r.shell = ch
        r.shellOut = ch.outputStream
        ring.clear()
        main.post { listeners.forEach { it.onTerminalReset() } }
        onConnected()
        log(if (p.useTmux) "✔ Running '${p.claudeCommand}' in tmux session '${p.tmuxSession}' (re-attached if it already existed)" else "✔ Running '${p.claudeCommand}'")
        setState(State.CONNECTED, "katana-job${jobNode?.let { " · $it" } ?: ""}")

        // Job-side keepalive. The katana connection has its own via setServerAliveInterval.
        val ka = thread(name = "job-keepalive", isDaemon = true) {
            try {
                while (js.isConnected && !r.stopped) {
                    Thread.sleep(30_000)
                    js.sendKeepAliveMsg()
                }
            } catch (_: Exception) {
            }
        }

        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = try {
                input.read(buf)
            } catch (e: IOException) {
                -1
            }
            if (n < 0) break
            if (n > 0) {
                val copy = buf.copyOf(n)
                ring.write(copy)
                main.post { listeners.forEach { it.onTerminalData(copy) } }
            }
        }
        ka.interrupt()
        r.shellOut = null
        if (r.stopped) return Outcome.USER_STOP
        val exit = ch.exitStatus
        val stillUp = js.isConnected && l.isConnected
        return if (exit >= 0 && stillUp) Outcome.REMOTE_EXIT else Outcome.DROPPED
    }

    // ---------------------------------------------------------------- ssh plumbing (see Ssh.kt)

    /** Host katana: known_hosts (trust on first use; a changed key is refused, like OpenSSH). */
    fun openLogin(p: Prefs): Session =
        Ssh.openLogin(SshKeys.privateKeyFile(app), SshKeys.knownHostsFile(app), p.user, p.host, p.port) { log(it) }

    /** Host katana-job: ProxyCommand ssh katana "~/resolve_node.sh", StrictHostKeyChecking no. */
    private fun openJob(p: Prefs, via: Session): Session =
        Ssh.openJob(SshKeys.privateKeyFile(app), p.user, via, p.proxyCommand) { line ->
            log("  [resolve_node] $line")
            Regex("Connecting to ([^:\\s]+):").find(line)?.let { jobNode = it.groupValues[1] }
        }

    fun exec(s: Session, cmd: String, timeoutMs: Long = 90_000, what: String = "a command on katana"): Ssh.ExecResult =
        Ssh.exec(s, cmd, timeoutMs, what)

    private fun checkStop(r: Run) {
        if (r.stopped) throw Stopped()
    }

    private fun sleepOrStop(r: Run, ms: Long) {
        val end = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < end) {
            checkStop(r)
            try {
                Thread.sleep(minOf(250L, end - SystemClock.elapsedRealtime()).coerceAtLeast(1))
            } catch (_: InterruptedException) {
                checkStop(r)
            }
        }
    }

    private fun elapsed(t0: Long): String {
        val s = (SystemClock.elapsedRealtime() - t0) / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun isAuthOrHostKeyError(e: Exception): Boolean {
        val m = e.message ?: return false
        return m.contains("Auth fail") || m.contains("Auth cancel") || m.contains("HostKey")
    }

    fun describe(e: Throwable): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            m.contains("Auth fail") -> "Authentication failed. Is the phone's public key in ~/.ssh/authorized_keys on katana? (⋮ → Settings & SSH key) [$m]"
            m.contains("HostKey has been changed") -> "katana's host key changed. If this is expected, use Settings → Forget known hosts. [$m]"
            m.contains("UnknownHost") || e is java.net.UnknownHostException || e.cause is java.net.UnknownHostException ->
                "Can't resolve the host. Is the UNSW VPN connected? [$m]"
            m.contains("timeout") || m.contains("timed out") -> "Timed out. Is the UNSW VPN connected? [$m]"
            e is JSchException && e.cause != null -> "$m (${e.cause?.javaClass?.simpleName}: ${e.cause?.message})"
            else -> m
        }
    }
}
