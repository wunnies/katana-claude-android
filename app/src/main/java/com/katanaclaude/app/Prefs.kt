package com.katanaclaude.app

import android.content.Context
import android.content.SharedPreferences

/** All user-editable settings. Defaults mirror the ~/.ssh/config and scripts on katana. */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun str(key: String, def: String): String = sp.getString(key, def)?.takeIf { it.isNotBlank() } ?: def
    private fun putStr(key: String, v: String) = sp.edit().putString(key, v.trim()).apply()
    private fun int(key: String, def: Int): Int = sp.getInt(key, def)
    private fun putInt(key: String, v: Int) = sp.edit().putInt(key, v).apply()
    private fun bool(key: String, def: Boolean): Boolean = sp.getBoolean(key, def)
    private fun putBool(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()

    var host: String
        get() = str("host", "katana.restech.unsw.edu.au"); set(v) = putStr("host", v)
    var port: Int
        get() = int("port", 22); set(v) = putInt("port", v)
    var user: String
        get() = str("user", "z5019209"); set(v) = putStr("user", v)

    /** Job script in the katana home directory, submitted with `qsub`. */
    var jobScript: String
        get() = str("jobScript", "j3-tunnel-96gb.sh"); set(v) = putStr("jobScript", v)
    /** PBS job name (#PBS -N) that resolve_node.sh looks for. */
    var jobName: String
        get() = str("jobName", "tunnel-sshd"); set(v) = putStr("jobName", v)
    /** The ProxyCommand that runs on katana and pipes to the job's sshd. */
    var proxyCommand: String
        get() = str("proxyCommand", "~/resolve_node.sh"); set(v) = putStr("proxyCommand", v)
    /** Directory to cd into on the job node before starting claude (empty = home). */
    var startDir: String
        get() = sp.getString("startDir", "/srv/scratch/wonglab/wunna")?.trim() ?: ""; set(v) = putStr("startDir", v)
    var claudeCommand: String
        get() = str("claudeCommand", "claude --resume"); set(v) = putStr("claudeCommand", v)

    var useTmux: Boolean
        get() = bool("useTmux", true); set(v) = putBool("useTmux", v)
    var tmuxSession: String
        get() = str("tmuxSession", "claude"); set(v) = putStr("tmuxSession", v)
    var tmuxMouse: Boolean
        get() = bool("tmuxMouse", true); set(v) = putBool("tmuxMouse", v)

    var pollSeconds: Int
        get() = int("pollSeconds", 10).coerceIn(3, 300); set(v) = putInt("pollSeconds", v)
    var autoReconnect: Boolean
        get() = bool("autoReconnect", true); set(v) = putBool("autoReconnect", v)
    var fontSize: Int
        get() = int("fontSize", 13).coerceIn(7, 30); set(v) = putInt("fontSize", v)

    /** True while the user wants to be connected; lets a restarted service resume. */
    var wantConnected: Boolean
        get() = bool("wantConnected", false); set(v) = putBool("wantConnected", v)
}
