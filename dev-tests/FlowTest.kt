import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.katanaclaude.app.ByteRing
import com.katanaclaude.app.Pbs
import com.katanaclaude.app.Ssh
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

val HOST = "127.0.0.1"
val PORT = 2022
val USER = "z5019209"
var failures = 0

fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "  PASS " else "  FAIL ") + name + (if (detail.isNotEmpty()) "  [$detail]" else ""))
    if (!ok) failures++
}

fun stripAnsi(s: String) = s.replace(Regex("\u001b\\[[0-9;?]*[ -/]*[@-~]|\u001b[()][0-9A-B]|\u001b[=>]|\u001b\\][^\u0007]*\u0007"), "")

fun genKey(priv: File, pub: File, type: Int) {
    val jsch = JSch()
    val kp = KeyPair.genKeyPair(jsch, type)
    FileOutputStream(priv).use { kp.writeOpenSSHv1PrivateKey(it, null) }
    val b = ByteArrayOutputStream(); kp.writePublicKey(b, "$USER@katana-claude-android")
    pub.writeText(String(b.toByteArray()).trim() + "\n")
    kp.dispose()
}

class Shell(val ch: ChannelExec) {
    val out = StringBuffer()
    private val ins = ch.inputStream
    private val o = ch.outputStream
    val t = thread(isDaemon = true) {
        val buf = ByteArray(16384)
        while (true) { val n = try { ins.read(buf) } catch (e: Exception) { -1 }; if (n < 0) break; out.append(String(buf, 0, n)) }
    }
    fun send(s: String) { o.write(s.toByteArray()); o.flush() }
    fun waitFor(text: String, ms: Long = 15000): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (stripAnsi(out.toString()).contains(text)) return true; Thread.sleep(100) }
        return false
    }
}

fun openTerm(js: com.jcraft.jsch.Session): Shell {
    val ch = js.openChannel("exec") as ChannelExec
    ch.setPty(true)
    ch.setPtyType("xterm-256color", 90, 30, 0, 0)
    ch.setCommand(Pbs.remoteCommand(Pbs.launchScript("/usr/local/bin/claude --resume", true, "claude", true, "/srv/scratch/wonglab/wunna")))
    val sh = Shell(ch)
    ch.connect(30000)
    return sh
}

fun main() {
    val dir = File("/tmp/kctest").apply { deleteRecursively(); mkdirs() }
    val priv = File(dir, "id_phone"); val pub = File(dir, "id_phone.pub"); val kh = File(dir, "known_hosts").apply { writeText("") }
    val logs = mutableListOf<String>()
    val log: (String) -> Unit = { logs += it; println("    log: $it") }

    println("1. Key generation (ed25519 via BouncyCastle, as on Android)")
    genKey(priv, pub, KeyPair.ED25519)
    val pubText = pub.readText().trim()
    check("public key is ssh-ed25519", pubText.startsWith("ssh-ed25519 "), pubText.take(40))
    check("private key is OpenSSH format", priv.readText().contains("BEGIN OPENSSH PRIVATE KEY"))

    println("2. Install key with password (keyboard-interactive/password), remote script")
    run {
        val jsch = JSch(); jsch.setKnownHosts(kh.absolutePath)
        val s = jsch.getSession(USER, HOST, PORT)
        s.setPassword("katanapw".toByteArray())
        s.userInfo = object : Ssh.TofuUserInfo(log), com.jcraft.jsch.UIKeyboardInteractive {
            override fun getPassword() = "katanapw"
            override fun promptPassword(message: String?) = true
            override fun promptKeyboardInteractive(d: String?, n: String?, i: String?, p: Array<out String>?, e: BooleanArray?) = Array(p?.size ?: 0) { "katanapw" }
        }
        s.setConfig("StrictHostKeyChecking", "ask")
        s.setConfig("PreferredAuthentications", "keyboard-interactive,password")
        s.connect(30000)
        val script = "mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys && " +
            "(grep -qxF ${Pbs.shq(pubText)} ~/.ssh/authorized_keys || echo ${Pbs.shq(pubText)} >> ~/.ssh/authorized_keys) && echo KEY_INSTALLED"
        val r = Ssh.exec(s, script)
        check("key installed", r.out.contains("KEY_INSTALLED"), r.err.trim())
        val r2 = Ssh.exec(s, script)  // idempotent
        val count = Ssh.exec(s, "grep -c . ~/.ssh/authorized_keys").out.trim()
        check("second install doesn't duplicate", r2.out.contains("KEY_INSTALLED") && count == "1", "lines=$count")
        s.disconnect()
        check("host key stored on first use (TOFU)", kh.readText().contains("[127.0.0.1]:2022"))
    }

    println("3. ssh katana with key; qstat; qsub; poll until R")
    val login = Ssh.openLogin(priv, kh, USER, HOST, PORT, log)
    check("key login works", login.isConnected, login.serverVersion)
    val q0 = Ssh.exec(login, Pbs.bashLc("qstat"))
    check("no job before submit", Pbs.bestAlive(Pbs.parseQstat(q0.out, "tunnel-sshd")) == null, q0.out.lines().size.toString())
    val sub = Ssh.exec(login, Pbs.bashLc(Pbs.submitScript("j3-tunnel-96gb.sh")))
    val id = Pbs.parseQsubId(sub.out)
    check("qsub returned a job id", id != null, "out=${sub.out.trim()} err=${sub.err.trim()}")
    val subFallback = Ssh.exec(login, Pbs.bashLc(Pbs.submitScript("does-not-exist.sh") + "; true"))
    // fallback picks j3-tunnel-96gb.sh; delete that extra job right away
    val fbId = Pbs.parseQsubId(subFallback.out)
    check("missing script falls back to j3-tunnel-96gb.sh", fbId != null && subFallback.err.contains("qsub ~/j3-tunnel-96gb.sh"), subFallback.err.trim())
    if (fbId != null) Ssh.exec(login, Pbs.bashLc("qdel ${Pbs.shq(fbId)}"))
    val t0 = System.currentTimeMillis()
    var state = ""
    var sawComment = false
    while (System.currentTimeMillis() - t0 < 60000) {
        val r = Ssh.exec(login, Pbs.bashLc(Pbs.qstatScript(id!!)))
        val (table, extra) = r.out.split("@@", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val j = Pbs.findById(Pbs.parseQstat(table, "tunnel-sshd"), id)
        if (Pbs.parseComment(extra) != null) sawComment = true
        state = j?.state ?: "gone"
        println("    [${(System.currentTimeMillis() - t0) / 1000}s] ${j?.id} $state  comment=${Pbs.parseComment(extra)}")
        if (state == "R") break
        Thread.sleep(2000)
    }
    check("job reached R", state == "R")
    check("PBS comment parsed", sawComment)
    check("existing running job is found for reuse", Pbs.bestAlive(Pbs.parseQstat(Ssh.exec(login, Pbs.bashLc("qstat")).out, "tunnel-sshd"))?.state == "R")

    println("4. ssh katana-job via ProxyCommand ~/resolve_node.sh over the katana connection")
    val stderr = mutableListOf<String>()
    val job = Ssh.openJob(priv, USER, login, "~/resolve_node.sh") { stderr += it; println("    [resolve_node] $it") }
    check("job session connected through proxy", job.isConnected, job.serverVersion)
    check("resolve_node said which node", stderr.any { it.startsWith("Connecting to localhost:2222") }, stderr.joinToString())

    println("5. interactive shell -> tmux -> claude --resume; arrow keys reach claude")
    var sh = openTerm(job)
    var ch = sh.ch
    val okStart = sh.waitFor("FAKE-CLAUDE started with args: --resume")
    if (!okStart) println("----- FULL SHELL OUTPUT -----\n" + sh.out.toString().replace("\u001b", "<ESC>") + "\n-----")
    check("claude started inside tmux", okStart)
    val cwd = Ssh.exec(login, Pbs.bashLc("tmux display -p -t claude '#{pane_current_path}'")).out.trim()
    check("claude started in /srv/scratch/wonglab/wunna", cwd == "/srv/scratch/wonglab/wunna", cwd)
    val inTmux = sh.waitFor("TERM=tmux") || sh.waitFor("TERM=screen")
    check("TERM inside tmux", inTmux)
    sh.send("\u001b[B")    // down arrow (normal cursor mode)
    check("down arrow delivered to claude", sh.waitFor("KEY[\$'\\E']") , stripAnsi(sh.out.toString()).takeLast(200))
    sh.send("\r")
    check("enter delivered", sh.waitFor("KEY['']") || sh.waitFor("KEY[\$'\\r']"), stripAnsi(sh.out.toString()).takeLast(200))
    ch.setPtySize(70, 20, 0, 0)
    Thread.sleep(800)
    val tmuxSize = Ssh.exec(login, "true")  // keep login busy concurrently
    check("login session still usable while tunnelled", tmuxSize.code == 0)

    println("6. drop the connection (phone loses network), reconnect, re-attach to the same tmux")
    ch.disconnect(); job.disconnect(); login.disconnect()
    Thread.sleep(1500)
    val alive = ProcessBuilder("bash", "-c", "su - $USER -c 'tmux ls'").redirectErrorStream(true).start().inputStream.bufferedReader().readText()
    check("tmux session survived the disconnect", alive.contains("claude:"), alive.trim())
    val login2 = Ssh.openLogin(priv, kh, USER, HOST, PORT, log)
    check("known host matches on second login (no prompt)", login2.isConnected && logs.count { it.startsWith("First connection") } == 1)
    val again = Pbs.bestAlive(Pbs.parseQstat(Ssh.exec(login2, Pbs.bashLc("qstat")).out, "tunnel-sshd"))
    check("reconnect reuses running job instead of qsub", again?.state == "R" && Pbs.shortId(again.id) == Pbs.shortId(id!!))
    val job2 = Ssh.openJob(priv, USER, login2, "~/resolve_node.sh") { }
    sh = openTerm(job2)
    ch = sh.ch
    check("re-attached: previous claude screen is redrawn", sh.waitFor("FAKE-CLAUDE started with args: --resume"), stripAnsi(sh.out.toString()).takeLast(300))
    val procs = ProcessBuilder("bash", "-c", "pgrep -u $USER -fc 'bash /usr/local/bin/claude'").start().inputStream.bufferedReader().readText().trim()
    check("only one claude process (attached, not restarted)", procs == "1", "count=$procs")
    sh.send("x")
    check("typing works after re-attach", sh.waitFor("KEY[x]"))
    // swipe-scroll in tmux mouse mode: SGR wheel-up reaches tmux and enters copy mode
    sh.send("\u001b[<64;10;10M")
    Thread.sleep(700)
    val mode = Ssh.exec(login2, Pbs.bashLc("tmux display -p -t claude '#{pane_in_mode}'")).out.trim()
    check("wheel-up (from swipe) enters tmux copy mode", mode == "1", "pane_in_mode=$mode")
    sh.send("q")
    ch.disconnect(); job2.disconnect()

    println("7. host key change is refused")
    val orig = kh.readText()
    val other = File("/tmp/kctest/otherkey"); ProcessBuilder("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", other.path).start().waitFor()
    kh.writeText("[127.0.0.1]:2022 " + File(other.path + ".pub").readText().split(" ").take(2).joinToString(" ") + "\n")
    val refused = try { Ssh.openLogin(priv, kh, USER, HOST, PORT, log).disconnect(); false } catch (e: Exception) { println("    -> ${e.message}"); e.message!!.contains("HostKey has been changed") }
    check("changed host key -> refused", refused)
    kh.writeText(orig)

    println("8. qdel ends the job (and tmux/claude with it)")
    Ssh.exec(login2, Pbs.bashLc("qdel ${Pbs.shq(Pbs.shortId(id!!))}"))
    Thread.sleep(6000)   // a running job sits in E (exiting) briefly
    val after = Pbs.parseQstat(Ssh.exec(login2, Pbs.bashLc("qstat")).out, "tunnel-sshd")
    check("job gone after qdel", after.isEmpty(), after.toString())
    login2.disconnect()

    println("9. ByteRing")
    val ring = ByteRing(10)
    ring.write("hello".toByteArray()); ring.write("world!!".toByteArray())
    check("ring keeps last bytes", String(ring.snapshot()) == "lloworld!!", String(ring.snapshot()))
    ring.write("0123456789ABCDEF".toByteArray())
    check("ring oversize write", String(ring.snapshot()) == "6789ABCDEF", String(ring.snapshot()))

    println("10. parsing edge cases")
    val sample = """
        Job id            Name             User              Time Use S Queue
        ----------------  ---------------- ----------------  -------- - -----
        123.kman          tunnel-sshd      z5019209          00:00:00 Q normal
        124.kman          tunnel-sshd      z5019209          01:02:03 R normal
        125.kman          other            z5019209                 0 R gpu
        126.kman          a-very-long-job* z5019209          00:00:00 R normal
    """.trimIndent()
    val js = Pbs.parseQstat(sample, "tunnel-sshd")
    check("two tunnel-sshd jobs parsed", js.size == 2)
    check("best = running one", Pbs.bestAlive(js)?.id == "124.kman")
    check("truncated long names match", Pbs.parseQstat(sample, "a-very-long-job-name").size == 1)
    check("qsub id with login noise", Pbs.parseQsubId("Loading modules...\n7000123.kman.restech.unsw.edu.au\n") == "7000123.kman.restech.unsw.edu.au")

    println(if (failures == 0) "\nALL PASSED" else "\n$failures FAILED")
    System.exit(if (failures == 0) 0 else 1)
}
