# Katana Claude (Android)

One button on your phone does all of this:

1. `ssh katana` (you're already on the UNSW VPN)
2. `qsub j3-tunnel-96gb.sh`, or reuses a `tunnel-sshd` job that's already queued or running, so you never get a duplicate
3. polls `qstat` until the job is `R`, showing a live log the whole time (usually a few minutes)
4. leaves the katana shell and runs `ssh katana-job` through `ProxyCommand ssh katana "~/resolve_node.sh"`
5. runs `claude --resume` inside **tmux** on the job node, in a full terminal

Leaving the app (or swiping it away) doesn't drop the session: a foreground service holds the
SSH connection, shown by a notification with a **Disconnect** button. Even if the phone loses
the connection, claude keeps running in tmux on the job node. The app reconnects automatically
(or you press **Start**), and it re-attaches to the same tmux session, so long agent runs carry on.

## Get the APK

**Option A: GitHub Actions (no tools needed).** Push this folder to a private GitHub repo.
The included workflow builds the APK on every push. Download it from
*Actions → latest run → Artifacts → KatanaClaude-apk* (it's a zip containing `app-debug.apk`).

**Option B: Android Studio on your Mac.** Open this folder, wait for Gradle sync, then
*Build → Build App Bundle(s) / APK(s) → Build APK(s)*. The APK is at
`app/build/outputs/apk/debug/app-debug.apk`. Or run `./gradlew assembleDebug` with JDK 17 and the Android SDK installed.

Install it on the phone (allow "install unknown apps" for your browser or file manager when asked).
Every build is signed with the same key (`app/katana-claude.keystore`), so new builds install
over old ones and keep the SSH key stored in the app.

## First-time setup (once)

1. Open the app → **⋮ → Settings & SSH key → Generate key** (ed25519).
2. Put the public key on katana, using either:
   - **Copy Mac command**, then paste it into a terminal on your Mac (where `ssh katana` works). It appends the key to `~/.ssh/authorized_keys` without duplicating it.
   - **Install with katana password**: logs in once from the phone with your zID password. The password isn't stored.
   - Or **Copy public key** / **Share…** and add it by hand.
3. **Test connection** checks the login, `qsub`/`qstat`/`tmux`/`claude` on PATH, and that the job script and `resolve_node.sh` exist.
4. Optional: **Battery: don't optimise**. Some phones kill background apps even with a notification.

You can also **Import key** (paste or file) to reuse an existing key such as `id_gadi`.
Passphrase-protected keys are decrypted once on import.

The tunnel sshd in the job uses `~/.ssh/sshd_tunnel_config`. If that file sets its own
`AuthorizedKeysFile`, make sure the phone's key is in that file too. The key installer prints the line it finds.

## Using it

- **Start** runs steps 1–5. The log view shows each step (`[2:15] job 7123456.kman: Q (queued)`, PBS comments, `resolve_node.sh` messages), and the app switches to the terminal when claude is up.
- **Picking a conversation without arrow keys:** the key bar above the keyboard has **↑ ↓ ← →** (hold to repeat), **⏎**, **Esc**, **Tab**, **⇧Tab** (Claude's mode toggle), **Ctrl** (sticky: tap it, then a letter), **^C**, **PgUp/PgDn**, **Home/End**, **/** and **⌫**.
- **Typing prompts:** tap the terminal for direct keyboard input, or use the box at the bottom with autocorrect and multi-line text. **Send** pastes it (as a bracketed paste, so newlines stay newlines) and presses Enter.
- **Scrolling:** swipe on the terminal. With tmux mouse mode (on by default), swiping back enters tmux copy mode, and scrolling to the bottom or pressing `q` returns.
- **⋮ menu:** font size, show/hide the compose box, copy terminal text, copy log, **End job (qdel)**, Disconnect.
- **Stop / Disconnect** only closes SSH. The PBS job, tmux and claude keep running until walltime (8 h) or **End job**.

## Settings (defaults match your setup)

| Setting | Default |
|---|---|
| Login host / user | `katana.restech.unsw.edu.au` / `z5019209` |
| Job script (in `~`) | `j3-tunnel-96gb.sh`, falling back to `j3-sshtunnel-96gb.sh` if not found |
| PBS job name | `tunnel-sshd` |
| ProxyCommand on katana | `~/resolve_node.sh` |
| Command on the node | `claude --resume` |
| tmux session | `claude` (mouse mode on) |
| qstat poll interval | 10 s |

## How it's built

- `TunnelManager.kt`: the whole flow, reconnect with backoff, per-run session ownership.
- `Ssh.kt`: JSch plumbing. `ExecChannelProxy` reproduces `ProxyCommand ssh katana "~/resolve_node.sh"` by running it on an exec channel of the katana connection and speaking SSH over its stdin/stdout.
- `Pbs.kt`: `qstat`/`qsub` parsing and the remote commands. Claude is started with `exec "$SHELL" -lic '…tmux new-session -A -s claude …'` on a pty, so PATH and modules match your normal login.
- `TunnelService.kt`: foreground service with wake and Wi-Fi locks. If Android kills it, it restarts and resumes.
- `assets/terminal.*`: xterm.js 6 terminal, key bar, compose box and swipe scrolling, in a WebView.
- Host key for katana: trust on first use, and a changed key is refused (Settings → Forget known hosts to reset). `katana-job` uses `StrictHostKeyChecking no`, as in your ssh config.

`dev-tests/` has the JVM end-to-end test and the mock cluster used to check the flow (fake
`qsub`/`qstat`/`qdel`, your real job and resolve scripts, and two sshds), plus the headless-browser test of the terminal page.
