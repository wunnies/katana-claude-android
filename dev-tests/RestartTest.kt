import com.jcraft.jsch.ChannelExec
import com.katanaclaude.app.JobOps
import com.katanaclaude.app.Pbs
import com.katanaclaude.app.Ssh
import java.io.File

// Restart flow as TunnelManager runs it: find job -> endJobs(wait) -> none alive -> qsub -> R -> tunnel lands on the NEW job.
fun main() {
    var fails = 0
    fun check(n: String, ok: Boolean, d: String = "") { println((if (ok) "  PASS " else "  FAIL ") + n + if (d.isNotEmpty()) "  [$d]" else ""); if (!ok) fails++ }
    val log: (String) -> Unit = { println("    log: $it") }
    val key = File("/tmp/kctest/id_phone"); val kh = File("/tmp/kctest/known_hosts")
    val l = Ssh.openLogin(key, kh, "z5019209", "127.0.0.1", 2022, log)
    fun jobs() = Pbs.parseQstat(Ssh.exec(l, Pbs.bashLc("qstat")).out, "tunnel-sshd")
    fun waitR(id: String) { repeat(30) { if (Pbs.findById(jobs(), id)?.state == "R") return; Thread.sleep(2000) } }

    println("A. nothing to end")
    check("endJobs with no job is a no-op", JobOps.endJobs(l, "tunnel-sshd", waitUntilGone = true, log = log, sleep = { Thread.sleep(it) }).isEmpty())

    println("B. running job -> restart")
    val old = Pbs.parseQsubId(Ssh.exec(l, Pbs.bashLc(Pbs.submitScript("j3-tunnel-96gb.sh"))).out)!!
    waitR(old)
    check("old job running", Pbs.findById(jobs(), old)?.state == "R")
    Thread.sleep(3000) // let its sshd come up
    val t0 = System.currentTimeMillis()
    val ended = JobOps.endJobs(l, "tunnel-sshd", waitUntilGone = true, log = log, sleep = { Thread.sleep(it) })
    val waited = System.currentTimeMillis() - t0
    check("qdel'd the old job", ended == listOf(Pbs.shortId(old)), ended.toString())
    check("waited for it to leave qstat (state E)", waited >= 3000 && jobs().none { Pbs.shortId(it.id) == Pbs.shortId(old) }, "${waited}ms")
    check("so nothing is reused", Pbs.bestAlive(jobs()) == null)
    val new = Pbs.parseQsubId(Ssh.exec(l, Pbs.bashLc(Pbs.submitScript("j3-tunnel-96gb.sh"))).out)!!
    check("new job id differs", Pbs.shortId(new) != Pbs.shortId(old), "$old -> $new")
    waitR(new)
    val stderr = mutableListOf<String>()
    val js = Ssh.openJob(key, "z5019209", l, "~/resolve_node.sh") { stderr += it }
    check("resolve_node tunnels into the NEW job", stderr.any { it.contains("(job $new)") }, stderr.joinToString())
    js.disconnect()

    println("C. queued job -> restart")
    val q = Pbs.parseQsubId(Ssh.exec(l, Pbs.bashLc("FAKE_QUEUE_SECS=600 qsub j3-tunnel-96gb.sh")).out)!!
    val ended2 = JobOps.endJobs(l, "tunnel-sshd", waitUntilGone = true, log = log, sleep = { Thread.sleep(it) })
    check("ends both the running and the queued job", ended2.toSet() == setOf(Pbs.shortId(new), Pbs.shortId(q)), ended2.toString())
    check("all gone", jobs().isEmpty(), jobs().toString())
    l.disconnect()
    println(if (fails == 0) "RESTART OK" else "$fails FAILED")
    System.exit(if (fails == 0) 0 else 1)
}
