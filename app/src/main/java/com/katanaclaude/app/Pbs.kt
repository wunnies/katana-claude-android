package com.katanaclaude.app

/** Pure helpers for PBS output parsing and remote shell command building (no Android deps). */
object Pbs {
    data class Job(val id: String, val name: String, val state: String)

    /** Job states in which the job still exists and may (eventually) run. */
    val ALIVE = setOf("Q", "R", "H", "W", "T", "S", "B", "M")

    fun describe(state: String): String = when (state) {
        "Q" -> "queued"
        "R" -> "running"
        "H" -> "held"
        "W" -> "waiting for start time"
        "T" -> "being moved"
        "S" -> "suspended"
        "E" -> "exiting"
        "F" -> "finished"
        "X" -> "finished (subjob)"
        "B" -> "array job begun"
        "M" -> "moved"
        else -> state
    }

    fun shortId(id: String): String = id.substringBefore('.')

    /**
     * Parses default `qstat` output:
     *   Job id            Name             User              Time Use S Queue
     *   ----------------  ---------------- ----------------  -------- - -----
     *   1234567.kman      tunnel-sshd      z5019209          00:00:01 R normal
     * qstat truncates long names (marking them with '*'), so long names match by prefix.
     */
    fun parseQstat(out: String, jobName: String): List<Job> {
        val jobs = ArrayList<Job>()
        for (raw in out.lineSequence()) {
            val t = raw.trim().split(Regex("\\s+"))
            if (t.size < 6) continue
            if (!t[0].first().isDigit()) continue
            val name = t[1]
            val matches = name == jobName ||
                (name.endsWith("*") && jobName.startsWith(name.dropLast(1))) ||
                (jobName.length > 15 && name.startsWith(jobName.take(15)))
            if (!matches) continue
            jobs += Job(t[0], name, t[t.size - 2])
        }
        return jobs
    }

    /** Running first, then queued/held, and so on. Jobs that are exiting or finished are ignored. */
    fun bestAlive(jobs: List<Job>): Job? {
        val order = listOf("R", "Q", "W", "H", "T", "S", "B", "M")
        return jobs.filter { it.state in ALIVE }.minByOrNull { order.indexOf(it.state) }
    }

    fun findById(jobs: List<Job>, id: String): Job? = jobs.firstOrNull { shortId(it.id) == shortId(id) }

    /** qsub prints e.g. "1234567.kman.restech.unsw.edu.au"; login scripts may print other lines too. */
    fun parseQsubId(out: String): String? =
        out.lineSequence().map { it.trim() }.lastOrNull { Regex("^\\d+(\\[\\d*])?\\.[\\w.-]+$").matches(it) }

    /** Extracts the PBS `comment = ...` line (e.g. "Not Running: Insufficient amount of resource: ncpus"). */
    fun parseComment(out: String): String? =
        out.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("comment = ") }?.removePrefix("comment = ")

    /** Single-quotes a string for POSIX shells. */
    fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Runs a script in a bash login shell, so PBS commands and modules are on PATH. */
    fun bashLc(script: String): String = "bash -lc " + shq(script)

    fun submitScript(preferred: String): String {
        val candidates = linkedSetOf(preferred, "j3-tunnel-96gb.sh", "j3-sshtunnel-96gb.sh").filter { it.isNotBlank() }
        val list = candidates.joinToString(" ") { shq(it) }
        return "cd ~ || exit 1; for f in $list; do if [ -f \"\$f\" ]; then echo \"qsub ~/\$f\" >&2; exec qsub \"\$f\"; fi; done; " +
            "echo \"No job script found in ~ (tried: ${candidates.joinToString(", ")})\" >&2; exit 2"
    }

    fun qstatScript(jobId: String?): String =
        if (jobId == null) "qstat"
        else "qstat; echo '@@'; qstat -f ${shq(jobId)} 2>/dev/null | grep -E '^ +comment = '"

    /**
     * The script run on the job node (step 5). With tmux, claude runs inside a tmux session, so it
     * keeps running if the phone drops off, and the next connection re-attaches (-A) to the same session.
     * When claude exits you're left at a shell, still inside tmux.
     */
    fun launchScript(claudeCmd: String, useTmux: Boolean, session: String, mouse: Boolean): String {
        val shellAfter = "exec \"\${SHELL:-bash}\" -l"
        if (!useTmux) return "$claudeCmd; $shellAfter"
        val inner = shq("$claudeCmd; $shellAfter")
        val s = shq(session)
        val mouseOpt = if (mouse) " \\; set-option -g mouse on" else ""
        return "if command -v tmux >/dev/null 2>&1; then exec tmux new-session -A -s $s $inner$mouseOpt; " +
            "elif command -v screen >/dev/null 2>&1; then exec screen -D -R -S $s sh -c $inner; " +
            "else echo 'tmux/screen not found: claude will stop if the connection drops'; $claudeCmd; $shellAfter; fi"
    }

    /**
     * Runs [script] in your login shell as an interactive shell (-lic), so PATH, modules and
     * aliases match what you get when you type `claude` yourself. It runs directly on a pty
     * exec channel rather than being typed into a prompt, so it can't race the prompt.
     */
    fun remoteCommand(script: String): String = "exec \"\${SHELL:-bash}\" -lic " + shq(script)
}
