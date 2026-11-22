# Dev tests

These were run while building the app. The mock "katana" ran on one Linux box.

- `mock-cluster/`: fake PBS (`qsub` queues a job for ~8 s, then really runs the job script; `qstat` prints
  default and `-f` formats; `qdel`), a fake `claude` that echoes keys, your real `resolve_node.sh` and
  `j3-tunnel-96gb.sh`, a login sshd on :2022 and the job's own sshd on :2222.
- `FlowTest.kt`: drives the app's real `Pbs`/`Ssh`/`ByteRing` code through the full flow: ed25519 keygen,
  password key install, trust on first use, qsub (with script fallback), qstat polling, the ProxyCommand
  tunnel, tmux + claude, arrow keys, drop and re-attach, tmux copy mode from swipe, host-key-change
  refusal, qdel. 30/30 passed.
- `Imp.kt`: importing ed25519 (with passphrase), RSA, PEM RSA and ECDSA keys, then logging in. All passed.
- `test_term.py`: Playwright phone emulation of `terminal.html` (keys, Ctrl, cursor modes, bracketed paste,
  swipe → scrollback, wheel reports or arrows, resize). All passed.

Build the classpath for the Kotlin tests with JSch 2.28.8 and bcprov 1.86, plus the app's
`Pbs.kt`, `Ssh.kt` and `ByteRing.kt`.
