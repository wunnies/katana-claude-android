package com.katanaclaude.app

import com.jcraft.jsch.Session

/** PBS job operations over an SSH session (no Android dependencies, so they can be tested on a JVM). */
object JobOps {

    /**
     * Ends every `jobName` job of yours with qdel. With [waitUntilGone], it then waits until those jobs
     * have left qstat entirely, because resolve_node.sh connects to the first `jobName` job that has a
     * node and would otherwise pick a job that's still exiting. Returns the ids it ended.
     */
    fun endJobs(
        s: Session,
        jobName: String,
        waitUntilGone: Boolean,
        log: (String) -> Unit,
        sleep: (Long) -> Unit,
        maxWaitMs: Long = 180_000,
    ): List<String> {
        val jobs = Pbs.parseQstat(Ssh.exec(s, Pbs.bashLc("qstat"), what = "qstat on katana").out, jobName)
            .filter { it.state != "F" }
        // Only jobs qstat still lists (a remembered id that has already finished would make qdel complain).
        val ids = jobs.map { Pbs.shortId(it.id) }.distinct()
        if (ids.isEmpty()) {
            log("No existing $jobName job to end.")
            return emptyList()
        }
        log("▶ qdel ${ids.joinToString(" ")}  (${jobs.joinToString { "${Pbs.shortId(it.id)} ${Pbs.describe(it.state)}" }})")
        val r = Ssh.exec(s, Pbs.bashLc("qdel " + ids.joinToString(" ") { Pbs.shq(it) }), what = "qdel on katana")
        val msg = (r.err + " " + r.out).trim()
        if (r.code != 0 && msg.isNotEmpty()) log("  qdel: $msg")
        if (!waitUntilGone) return ids

        val t0 = System.nanoTime()
        while (true) {
            val left = Pbs.parseQstat(Ssh.exec(s, Pbs.bashLc("qstat"), what = "qstat on katana").out, jobName)
                .filter { Pbs.shortId(it.id) in ids && it.state != "F" }
            if (left.isEmpty()) {
                log("✔ Old job ended")
                return ids
            }
            val waited = (System.nanoTime() - t0) / 1_000_000
            if (waited > maxWaitMs) {
                log("⚠ Old job still listed after ${waited / 1000}s (${left.joinToString { "${it.id} ${it.state}" }}); continuing anyway")
                return ids
            }
            log("  waiting for the old job to leave qstat (${left.joinToString { "${Pbs.shortId(it.id)} ${Pbs.describe(it.state)}" }})")
            sleep(5_000)
        }
    }
}
