package com.katanaclaude.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.jcraft.jsch.JSch
import com.jcraft.jsch.UIKeyboardInteractive
import kotlin.concurrent.thread

class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var col: LinearLayout
    private lateinit var pubView: TextView

    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var user: EditText
    private lateinit var script: EditText
    private lateinit var jobName: EditText
    private lateinit var proxy: EditText
    private lateinit var command: EditText
    private lateinit var poll: EditText
    private lateinit var tmux: CheckBox
    private lateinit var tmuxSession: EditText
    private lateinit var tmuxMouse: CheckBox
    private lateinit var reconnect: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TunnelManager.init(this)
        prefs = Prefs(this)
        window.statusBarColor = Ui.BAR
        window.navigationBarColor = Ui.BAR

        val pad = Ui.dp(this, 16)
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad * 3)
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            addView(col)
        }
        setContentView(scroll)

        // ----------------------------------------------------------- key
        header("SSH key for this phone")
        note("Katana (and the tunnel sshd in your job) must accept this key. Add the public key below to ~/.ssh/authorized_keys on katana. The private key never leaves the app.")
        pubView = Ui.mono(this).apply {
            setPadding(Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 8))
            setBackgroundColor(Ui.BAR)
        }
        col.addView(pubView, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(this@SettingsActivity, 6) })
        row(
            Ui.button(this, "Generate key") { generate() },
            Ui.button(this, "Copy public key") { withPub { Ui.copy(this, "public key", it) } },
        )
        row(
            Ui.button(this, "Share…") { withPub { shareText(it) } },
            Ui.button(this, "Copy Mac command") { withPub { Ui.copy(this, "Mac command", SshKeys.macInstallCommand(it)) } },
        )
        note("\"Copy Mac command\" copies a one-liner that appends the key on katana. Paste it into a terminal on your Mac, where `ssh katana` already works. You can also install it from here with your zID password:")
        row(
            Ui.button(this, "Install with katana password") { installWithPassword() },
            Ui.button(this, "Test connection") { testConnection() },
        )
        row(
            Ui.button(this, "Import key (paste)") { importPaste() },
            Ui.button(this, "Import key (file)") { importFile() },
        )

        // ----------------------------------------------------------- connection
        header("Connection")
        host = field("Login host (Host katana)", prefs.host)
        port = field("Port", prefs.port.toString(), InputType.TYPE_CLASS_NUMBER)
        user = field("Username", prefs.user)
        script = field("Job script in ~ (qsub)", prefs.jobScript)
        note("If this file isn't found, j3-tunnel-96gb.sh and j3-sshtunnel-96gb.sh are tried as well.")
        jobName = field("PBS job name (#PBS -N)", prefs.jobName)
        proxy = field("ProxyCommand run on katana (Host katana-job)", prefs.proxyCommand)
        command = field("Command to run on the job node", prefs.claudeCommand)
        poll = field("qstat poll interval (seconds)", prefs.pollSeconds.toString(), InputType.TYPE_CLASS_NUMBER)
        tmux = check("Run inside tmux (claude survives disconnects; Start re-attaches)", prefs.useTmux)
        tmuxSession = field("tmux session name", prefs.tmuxSession)
        tmuxMouse = check("tmux mouse mode (swipe on the terminal to scroll back)", prefs.tmuxMouse)
        reconnect = check("Auto-reconnect when the connection drops", prefs.autoReconnect)
        row(Ui.button(this, "Save") { save(); Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show() })

        // ----------------------------------------------------------- maintenance
        header("Keep running in the background")
        note("The app runs a foreground service (with a notification) to hold the SSH connection when you leave it. Some phones still kill background apps, so allow unrestricted battery use:")
        row(
            Ui.button(this, "Battery: don't optimise") { batteryExemption() },
            Ui.button(this, "Forget known hosts") {
                SshKeys.knownHostsFile(this).writeText("")
                Toast.makeText(this, "Known hosts cleared", Toast.LENGTH_SHORT).show()
            },
        )

        refreshKey()
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    // ----------------------------------------------------------- key actions

    private fun refreshKey() {
        pubView.text = SshKeys.publicKey(this) ?: "No key yet. Tap \"Generate key\"."
    }

    private fun withPub(f: (String) -> Unit) {
        val pub = SshKeys.publicKey(this)
        if (pub == null) Toast.makeText(this, "Generate a key first", Toast.LENGTH_SHORT).show() else f(pub)
    }

    private fun comment() = "${prefs.user}@katana-claude-android"

    private fun generate() {
        val go = {
            thread {
                val r = try {
                    "Generated a new ${SshKeys.generate(this, comment())} key."
                } catch (e: Throwable) {
                    "Key generation failed: ${e.message}"
                }
                runOnUiThread { refreshKey(); Toast.makeText(this, r, Toast.LENGTH_LONG).show() }
            }
            Unit
        }
        if (SshKeys.hasKey(this)) {
            AlertDialog.Builder(this)
                .setTitle("Replace the existing key?")
                .setMessage("The old key will stop working from this phone. You'll need to add the new public key to katana.")
                .setPositiveButton("Replace") { _, _ -> go() }
                .setNegativeButton("Cancel", null)
                .show()
        } else go()
    }

    private fun shareText(text: String) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(i, "Share public key"))
    }

    private fun importPaste() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(context, 20), 0, Ui.dp(context, 20), 0) }
        val keyText = EditText(this).apply {
            hint = "-----BEGIN OPENSSH PRIVATE KEY-----…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 4
            maxLines = 8
            textSize = 11f
        }
        val pass = EditText(this).apply {
            hint = "Passphrase (if the key has one)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(keyText)
        box.addView(pass)
        AlertDialog.Builder(this)
            .setTitle("Import private key")
            .setView(box)
            .setPositiveButton("Import") { _, _ -> doImport(keyText.text.toString(), pass.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_KEY_FILE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_KEY_FILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val text = try {
            contentResolver.openInputStream(uri)!!.use { String(it.readBytes(), Charsets.UTF_8) }
        } catch (e: Exception) {
            Ui.info(this, "Couldn't read file", e.message ?: e.toString()); return
        }
        val pass = EditText(this).apply {
            hint = "Passphrase (leave empty if none)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Import this key?")
            .setView(pass)
            .setPositiveButton("Import") { _, _ -> doImport(text, pass.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doImport(text: String, pass: String) {
        thread {
            val msg = try {
                SshKeys.import(this, text, pass.ifEmpty { null }, comment())
                "Key imported."
            } catch (e: Throwable) {
                "Import failed: ${e.message}"
            }
            runOnUiThread { refreshKey(); Ui.info(this, "Import key", msg) }
        }
    }

    // ----------------------------------------------------------- remote actions

    private fun installWithPassword() {
        val pub = SshKeys.publicKey(this) ?: run { Toast.makeText(this, "Generate a key first", Toast.LENGTH_SHORT).show(); return }
        save()
        val pw = EditText(this).apply {
            hint = "Password for ${prefs.user}@${prefs.host}"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Install key on katana")
            .setMessage("Logs in once with your password (on the VPN) and appends the public key to ~/.ssh/authorized_keys. The password isn't stored.")
            .setView(pw)
            .setPositiveButton("Install") { _, _ -> runInstall(pub, pw.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runInstall(pub: String, password: String) {
        val progress = AlertDialog.Builder(this).setTitle("Installing key…").setMessage("Connecting to ${prefs.host}").setCancelable(false).show()
        thread {
            val log = StringBuilder()
            val result = try {
                val jsch = JSch()
                jsch.setKnownHosts(SshKeys.knownHostsFile(this).absolutePath)
                val s = jsch.getSession(prefs.user, prefs.host, prefs.port)
                s.setPassword(password.toByteArray(Charsets.UTF_8))
                s.userInfo = PasswordUserInfo(password) { log.append(it).append('\n') }
                s.setConfig("StrictHostKeyChecking", "ask")
                s.setConfig("PreferredAuthentications", "keyboard-interactive,password")
                s.connect(30_000)
                try {
                    val r = TunnelManager.exec(s, SshKeys.remoteInstallScript(pub) +
                        "; echo; echo \"sshd_tunnel_config: $(grep -i AuthorizedKeysFile ~/.ssh/sshd_tunnel_config 2>/dev/null || echo 'no AuthorizedKeysFile line (default ~/.ssh/authorized_keys)')\"")
                    if (r.out.contains("KEY_INSTALLED")) "✔ Key added to ~/.ssh/authorized_keys on ${prefs.host}.\n\n${r.out.replace("KEY_INSTALLED", "").trim()}\n\nNow press Test connection."
                    else "Something went wrong (exit ${r.code}):\n${r.out}\n${r.err}"
                } finally {
                    s.disconnect()
                }
            } catch (e: Exception) {
                "Failed: ${TunnelManager.describe(e)}\n\nIf katana needs a second factor, use \"Copy Mac command\" instead."
            }
            runOnUiThread {
                progress.dismiss()
                Ui.info(this, "Install key", (log.toString() + "\n" + result).trim())
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun testConnection() {
        if (!SshKeys.hasKey(this)) { Toast.makeText(this, "Generate a key first", Toast.LENGTH_SHORT).show(); return }
        save()
        val progress = AlertDialog.Builder(this).setTitle("Testing…").setMessage("ssh ${prefs.user}@${prefs.host}").setCancelable(false).show()
        thread {
            val result = try {
                val s = TunnelManager.openLogin(prefs)
                try {
                    val files = listOf(prefs.jobScript, "resolve_node.sh").joinToString(" ") { "~/" + Pbs.shq(it) }
                    val r = TunnelManager.exec(
                        s, Pbs.bashLc(
                            "echo \"✔ logged in to $(hostname) as $(whoami)\"; " +
                                "for c in qsub qstat tmux claude; do printf '%-6s ' \"\$c:\"; command -v \"\$c\" || echo 'NOT FOUND on login node'; done; " +
                                "ls -l $files 2>&1; echo; echo 'qstat:'; qstat 2>&1 | head -20"
                        )
                    )
                    r.out + r.err
                } finally {
                    s.disconnect()
                }
            } catch (e: Exception) {
                "❌ ${TunnelManager.describe(e)}"
            }
            runOnUiThread {
                progress.dismiss()
                Ui.info(this, "Test connection", result.trim())
            }
        }
    }

    @SuppressLint("BatteryLife")
    private fun batteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Already unrestricted", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ----------------------------------------------------------- form helpers

    private fun save() {
        prefs.host = host.text.toString()
        prefs.port = port.text.toString().toIntOrNull() ?: 22
        prefs.user = user.text.toString()
        prefs.jobScript = script.text.toString()
        prefs.jobName = jobName.text.toString()
        prefs.proxyCommand = proxy.text.toString()
        prefs.claudeCommand = command.text.toString()
        prefs.pollSeconds = poll.text.toString().toIntOrNull() ?: 10
        prefs.useTmux = tmux.isChecked
        prefs.tmuxSession = tmuxSession.text.toString()
        prefs.tmuxMouse = tmuxMouse.isChecked
        prefs.autoReconnect = reconnect.isChecked
    }

    private fun header(t: String) {
        col.addView(Ui.label(this, t, 17f, Ui.ACCENT, bold = true),
            LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(this@SettingsActivity, 18) })
    }

    private fun note(t: String) {
        col.addView(Ui.label(this, t, 13f, Ui.DIM),
            LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(this@SettingsActivity, 4) })
    }

    private fun row(vararg views: android.view.View) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        views.forEach { r.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        col.addView(r, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(this@SettingsActivity, 4) })
    }

    private fun field(label: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS): EditText {
        col.addView(Ui.label(this, label, 12f, Ui.DIM),
            LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(this@SettingsActivity, 10) })
        val e = EditText(this).apply {
            setText(value)
            inputType = type
            isSingleLine = true
            setTextColor(Ui.FG)
            textSize = 15f
        }
        col.addView(e, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        return e
    }

    private fun check(label: String, value: Boolean): CheckBox {
        val c = CheckBox(this).apply {
            text = label
            isChecked = value
            setTextColor(Ui.FG)
        }
        col.addView(c, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(this@SettingsActivity, 6) })
        return c
    }

    /** Password / keyboard-interactive login, used only for the one-off key install. */
    private class PasswordUserInfo(private val pw: String, log: (String) -> Unit) :
        Ssh.TofuUserInfo(log), UIKeyboardInteractive {
        override fun getPassword(): String = pw
        override fun promptPassword(message: String?): Boolean = true
        override fun promptKeyboardInteractive(
            destination: String?, name: String?, instruction: String?, prompt: Array<out String>?, echo: BooleanArray?,
        ): Array<String> = Array(prompt?.size ?: 0) { pw }
    }

    companion object {
        private const val REQ_KEY_FILE = 42
    }
}
