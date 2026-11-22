package com.katanaclaude.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray

class MainActivity : Activity(), TunnelManager.Listener {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var startBtn: Button
    private lateinit var viewBtn: Button
    private lateinit var web: WebView
    private lateinit var logScroll: ScrollView
    private lateinit var logView: TextView

    private var termReady = false
    private var attached = false
    private var showingTerminal = false
    private var composeShown = true
    private var lastState: TunnelManager.State? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TunnelManager.init(this)
        prefs = Prefs(this)
        window.statusBarColor = Ui.BAR
        window.navigationBarColor = Ui.BAR

        val pad = Ui.dp(this, 6)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.BAR)
            setPadding(pad * 2, pad, pad, pad)
        }
        status = Ui.label(this, "Idle", 13f, Ui.DIM).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        startBtn = Ui.button(this, "Start") { onStartStop() }
        viewBtn = Ui.button(this, "Term") { show(!showingTerminal) }
        val menuBtn = Ui.button(this, "⋮") {}
        menuBtn.setOnClickListener { showMenu(it) }
        bar.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        bar.addView(startBtn)
        bar.addView(viewBtn)
        bar.addView(menuBtn)

        val frame = FrameLayout(this)
        web = WebView(this)
        logView = Ui.mono(this).apply { setPadding(pad * 2, pad, pad * 2, pad * 4) }
        logScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(logView)
        }
        frame.addView(web, FrameLayout.LayoutParams(-1, -1))
        frame.addView(logScroll, FrameLayout.LayoutParams(-1, -1))

        root.addView(bar, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        setupWebView()
        show(TunnelManager.state == TunnelManager.State.CONNECTED)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun setupWebView() {
        web.setBackgroundColor(Ui.BG)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.textZoom = 100
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
        }
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("file:///android_asset/terminal.html")
    }

    override fun onStart() {
        super.onStart()
        attached = true
        TunnelManager.addListener(this)
        renderLog()
        onState(TunnelManager.state, TunnelManager.detail)
        if (termReady) replay()
    }

    override fun onStop() {
        attached = false
        TunnelManager.removeListener(this)
        super.onStop()
    }

    override fun onDestroy() {
        TunnelManager.removeListener(this)
        web.destroy()
        super.onDestroy()
    }

    /** Back hides the app instead of closing the terminal. The session keeps running either way. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    // ------------------------------------------------------------------ actions

    private fun onStartStop() {
        if (TunnelManager.state.active) {
            TunnelManager.disconnect()
            return
        }
        if (!SshKeys.hasKey(this)) {
            AlertDialog.Builder(this)
                .setTitle("SSH key needed")
                .setMessage("Generate (or import) an SSH key and add its public key to ~/.ssh/authorized_keys on katana first.")
                .setPositiveButton("Open settings") { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        show(false)
        TunnelManager.start(this)
    }

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "Settings & SSH key")
        m.menu.add(0, 2, 0, "Font larger")
        m.menu.add(0, 3, 0, "Font smaller")
        m.menu.add(0, 4, 0, if (composeShown) "Hide compose box" else "Show compose box")
        m.menu.add(0, 5, 0, "Copy terminal text")
        m.menu.add(0, 6, 0, "Copy log")
        m.menu.add(0, 7, 0, "End job (qdel)…")
        m.menu.add(0, 8, 0, "Disconnect (job keeps running)")
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> startActivity(Intent(this, SettingsActivity::class.java))
                2 -> setFont(prefs.fontSize + 1)
                3 -> setFont(prefs.fontSize - 1)
                4 -> {
                    composeShown = !composeShown
                    js("termSetCompose($composeShown)")
                }
                5 -> web.evaluateJavascript("termText()") { json ->
                    val text = try { JSONArray("[$json]").getString(0) } catch (_: Exception) { "" }
                    Ui.copy(this, "terminal text", text)
                }
                6 -> Ui.copy(this, "log", TunnelManager.logSnapshot().joinToString("\n"))
                7 -> AlertDialog.Builder(this)
                    .setTitle("End the PBS job?")
                    .setMessage("This runs qdel on the ${prefs.jobName} job. tmux and claude on the node stop with it.")
                    .setPositiveButton("End job") { _, _ -> show(false); TunnelManager.endJob(this) }
                    .setNegativeButton("Cancel", null)
                    .show()
                8 -> TunnelManager.disconnect()
            }
            true
        }
        m.show()
    }

    private fun setFont(n: Int) {
        prefs.fontSize = n
        js("termSetFont(${prefs.fontSize})")
    }

    private fun show(terminal: Boolean) {
        showingTerminal = terminal
        web.visibility = if (terminal) View.VISIBLE else View.INVISIBLE
        logScroll.visibility = if (terminal) View.GONE else View.VISIBLE
        viewBtn.text = if (terminal) "Log" else "Term"
        if (terminal) {
            web.requestFocus()
            js("termFit()")
        }
    }

    // ------------------------------------------------------------------ TunnelManager.Listener

    override fun onLog(line: String) {
        logView.append(line + "\n")
        logScroll.post { logScroll.scrollTo(0, logView.bottom) }
    }

    override fun onState(state: TunnelManager.State, detail: String) {
        status.text = if (detail.isBlank() || detail == state.label) state.label else "${state.label} · $detail"
        status.setTextColor(
            when (state) {
                TunnelManager.State.CONNECTED -> Ui.OK
                TunnelManager.State.ERROR -> Ui.ERR
                TunnelManager.State.IDLE -> Ui.DIM
                else -> Ui.WARN
            }
        )
        startBtn.text = if (state.active) "Stop" else "Start"
        if (state == TunnelManager.State.CONNECTED && lastState != TunnelManager.State.CONNECTED) show(true)
        if (state != TunnelManager.State.CONNECTED && lastState == TunnelManager.State.CONNECTED) show(false)
        lastState = state
    }

    override fun onTerminalData(data: ByteArray) {
        if (termReady && attached) js("termWrite('${Base64.encodeToString(data, Base64.NO_WRAP)}')")
    }

    override fun onTerminalReset() {
        if (termReady) js("termReset()")
    }

    // ------------------------------------------------------------------ terminal plumbing

    private fun js(code: String) {
        web.evaluateJavascript(code, null)
    }

    private fun renderLog() {
        val lines = TunnelManager.logSnapshot()
        logView.text = if (lines.isEmpty()) WELCOME else lines.joinToString("\n", postfix = "\n")
        logScroll.post { logScroll.scrollTo(0, logView.bottom) }
    }

    /** Repaints the terminal after the app comes back: replays recent output, then asks tmux to redraw. */
    private fun replay() {
        js("termReset()")
        val snap = TunnelManager.terminalSnapshot()
        var off = 0
        while (off < snap.size) {
            val n = minOf(48 * 1024, snap.size - off)
            js("termWrite('${Base64.encodeToString(snap, off, n, Base64.NO_WRAP)}')")
            off += n
        }
        TunnelManager.forceRedraw()
    }

    inner class Bridge {
        @JavascriptInterface
        fun input(s: String) = TunnelManager.sendInput(s.toByteArray(Charsets.UTF_8))

        @JavascriptInterface
        fun binary(s: String) = TunnelManager.sendInput(ByteArray(s.length) { s[it].code.toByte() })

        @JavascriptInterface
        fun resized(c: Int, r: Int) = TunnelManager.resize(c, r)

        @JavascriptInterface
        fun ready(c: Int, r: Int) {
            runOnUiThread {
                termReady = true
                TunnelManager.resize(c, r)
                js("termSetFont(${prefs.fontSize})")
                if (attached) replay()
            }
        }

        @JavascriptInterface
        fun log(s: String) {
            Log.d("KatanaTerm", s)
        }
    }

    companion object {
        private const val WELCOME = """Katana Claude

First-time setup:
 1. ⋮ → Settings & SSH key → Generate key.
 2. Put the public key on katana, using one of:
      • "Copy Mac command", then paste it into a Mac terminal, or
      • "Install with katana password", right from the phone.
 3. Connect your phone to the UNSW VPN.

Then press Start. The app will:
 • ssh to katana and qsub j3-tunnel-96gb.sh (or reuse a tunnel-sshd job that's already queued or running)
 • poll qstat until the job is R (usually a few minutes)
 • ssh katana-job through ~/resolve_node.sh
 • run 'claude --resume' inside tmux

Leaving the app keeps the connection open in the background (see the notification).
If the connection drops, claude keeps running in tmux, and Start re-attaches.

Terminal keys: ↑ ↓ pick a conversation in the --resume list, ⏎ opens it,
Esc interrupts Claude, ⇧Tab cycles modes. Swipe on the terminal to scroll.
Type long prompts in the box at the bottom and tap Send.
"""
    }
}
