import base64, json, sys
from playwright.sync_api import sync_playwright

URL = "file:///home/claude/KatanaClaude/app/src/main/assets/terminal.html"
fails = 0
def check(name, ok, detail=""):
    global fails
    print(("  PASS " if ok else "  FAIL ") + name + (f"  [{detail}]" if detail else ""))
    if not ok: fails += 1

INIT = """
window.__in = []; window.__ready = null; window.__resized = [];
window.Android = {
  input: s => window.__in.push(s), binary: s => window.__in.push('BIN:' + s),
  resized: (c, r) => window.__resized.push([c, r]), ready: (c, r) => { window.__ready = [c, r]; }, log: s => console.log('LOG', s)
};
"""

def b64(s): return base64.b64encode(s.encode()).decode()

with sync_playwright() as p:
    b = p.chromium.launch()
    ctx = b.new_context(viewport={"width": 393, "height": 760}, device_scale_factor=2.75, is_mobile=True, has_touch=True)
    page = ctx.new_page()
    errs = []
    page.on("pageerror", lambda e: errs.append(str(e)))
    page.add_init_script(INIT)
    page.goto(URL)
    page.wait_for_function("window.__ready !== null")
    cols, rows = page.evaluate("window.__ready")
    check("ready() called with a sane size", cols >= 40 and rows >= 15, f"{cols}x{rows}")
    check("no JS errors on load", not errs, "; ".join(errs))

    def taps(label):
        el = page.locator(".k", has_text=label).first
        el.tap()
    def take():
        v = page.evaluate("window.__in.splice(0)")
        return v

    hello = "Hello from katana-job \u2714\r\n\x1b[32mgreen\x1b[0m\r\n"
    page.evaluate(f"termWrite('{b64(hello)}')")
    page.wait_for_timeout(100)
    txt = page.evaluate("termText()")
    check("termWrite renders UTF-8 text", "Hello from katana-job ✔" in txt and "green" in txt, txt[:60])

    take()
    taps("↑"); taps("↓"); taps("Esc"); taps("⇧Tab"); taps("^C"); taps("⏎")
    got = take()
    check("extra keys (normal cursor mode)", got == ["\x1b[A", "\x1b[B", "\x1b", "\x1b[Z", "\x03", "\r"], json.dumps(got))

    page.evaluate(f"termWrite('{b64(chr(27) + '[?1h')}')")   # application cursor keys (DECCKM), as TUIs set
    page.wait_for_timeout(50)
    taps("↑"); taps("→")
    got = take()
    check("arrows follow application cursor mode", got == ["\x1bOA", "\x1bOC"], json.dumps(got))

    # sticky Ctrl + typed letter through xterm's own input
    page.locator(".xterm-helper-textarea").focus()
    taps("Ctrl")
    check("Ctrl shows as armed", "on" in page.locator(".k", has_text="Ctrl").first.get_attribute("class"))
    page.keyboard.type("c")
    page.keyboard.type("d")
    got = take()
    check("Ctrl+c -> ^C then plain d", got == ["\x03", "d"], json.dumps(got))

    # compose box: plain paste, then bracketed paste
    page.locator("#msg").fill("fix the bug\nin resolve_node.sh")
    page.locator("#send").tap()
    page.wait_for_timeout(200)
    got = take()
    check("Send = paste + Enter (no bracketed mode)", got == ["fix the bug\rin resolve_node.sh", "\r"], json.dumps(got))
    check("compose box cleared", page.locator("#msg").input_value() == "")
    page.evaluate(f"termWrite('{b64(chr(27) + '[?2004h')}')")
    page.wait_for_timeout(50)
    page.locator("#msg").fill("two\nlines")
    page.locator("#send").tap()
    page.wait_for_timeout(200)
    got = take()
    check("Send uses bracketed paste when the app enables it", got == ["\x1b[200~two\rlines\x1b[201~", "\r"], json.dumps(got))
    page.locator("#send").tap()
    page.wait_for_timeout(50)
    check("Send with empty box = Enter", take() == ["\r"])

    cdp = ctx.new_cdp_session(page)
    def swipe(y0, y1, steps=12):
        x = 180
        cdp.send("Input.dispatchTouchEvent", {"type": "touchStart", "touchPoints": [{"x": x, "y": y0}]})
        for i in range(1, steps + 1):
            y = y0 + (y1 - y0) * i / steps
            cdp.send("Input.dispatchTouchEvent", {"type": "touchMove", "touchPoints": [{"x": x, "y": y}]})
        cdp.send("Input.dispatchTouchEvent", {"type": "touchEnd", "touchPoints": []})
        page.wait_for_timeout(100)

    # normal buffer: swipe scrolls xterm's scrollback, sends nothing
    page.evaluate("termReset()")
    page.evaluate(f"termWrite('{b64(''.join(f'line {i}' + chr(13) + chr(10) for i in range(200)))}')")
    page.wait_for_timeout(100)
    take()
    before = page.evaluate("__term.buffer.active.viewportY")
    swipe(200, 450)   # finger down = scroll back
    after = page.evaluate("__term.buffer.active.viewportY")
    check("swipe scrolls scrollback in normal screen", after < before and take() == [], f"viewportY {before}->{after}")

    # alternate screen + SGR mouse (tmux with mouse on): swipe -> wheel reports
    page.evaluate(f"termWrite('{b64(chr(27)+'[?1049h'+chr(27)+'[?1000h'+chr(27)+'[?1002h'+chr(27)+'[?1006h')}')")
    page.wait_for_timeout(50)
    take()
    swipe(200, 400)
    got = "".join(take())
    check("swipe down in tmux mouse mode -> wheel-up reports", "\x1b[<64;" in got and "\x1b[<65;" not in got, repr(got[:60]))
    swipe(400, 200)
    got = "".join(take())
    check("swipe up in tmux mouse mode -> wheel-down reports", "\x1b[<65;" in got, repr(got[:60]))

    # alternate screen without mouse tracking: wheel -> arrow keys (xterm behaviour)
    page.evaluate(f"termWrite('{b64(chr(27)+'[?1006l'+chr(27)+'[?1002l'+chr(27)+'[?1000l')}')")
    page.wait_for_timeout(50)
    take()
    swipe(200, 400)
    got = "".join(take())
    check("swipe in alt screen without mouse -> arrow keys", ("\x1b[A" in got or "\x1bOA" in got), repr(got[:40]))

    # a tap (no move) doesn't send anything or scroll
    take()
    cdp.send("Input.dispatchTouchEvent", {"type": "touchStart", "touchPoints": [{"x": 150, "y": 200}]})
    cdp.send("Input.dispatchTouchEvent", {"type": "touchEnd", "touchPoints": []})
    page.wait_for_timeout(100)
    check("tap sends nothing", take() == [])

    # font size + resize reporting
    page.evaluate("window.__resized.splice(0)")
    page.evaluate("termSetFont(16)")
    page.wait_for_timeout(200)
    rs = page.evaluate("window.__resized")
    check("font change reports new size", bool(rs) and rs[-1][0] < cols, json.dumps(rs[-1:] if rs else rs))
    page.set_viewport_size({"width": 393, "height": 450})   # soft keyboard opens
    page.wait_for_timeout(400)
    rs2 = page.evaluate("window.__resized")
    check("viewport shrink (keyboard) reports fewer rows", rs2 and rs2[-1][1] < rs[-1][1], json.dumps(rs2[-1:]))
    page.set_viewport_size({"width": 393, "height": 760})
    page.evaluate("termSetFont(13)")
    page.wait_for_timeout(300)

    # screenshot with a Claude-like screen for a visual check
    page.evaluate("termReset()")
    demo = ("\x1b[38;5;174m╭───────────────────────────────────────╮\r\n│ ✻ Welcome to Claude Code!             │\r\n╰───────────────────────────────────────╯\x1b[0m\r\n\r\n"
            " Resume a conversation\r\n\r\n \x1b[38;5;153m❯ 1. katana analysis of mem.log  (2h ago)\x1b[0m\r\n   2. paper draft figures         (1d ago)\r\n   3. resolve_node debugging      (3d ago)\r\n\r\n"
            "\x1b[2m ↑/↓ to select · Enter to resume · Esc to exit\x1b[0m\r\n")
    page.evaluate(f"termWrite('{b64(demo)}')")
    page.wait_for_timeout(200)
    page.screenshot(path="/tmp/webtest/terminal_phone.png")
    check("no JS errors overall", not errs, "; ".join(errs))
    b.close()

print("\nALL PASSED" if fails == 0 else f"\n{fails} FAILED")
sys.exit(1 if fails else 0)
