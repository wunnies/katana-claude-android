/* Terminal page: xterm.js display + phone-friendly keys. Talks to Kotlin through window.Android. */
(function () {
  'use strict';

  var A = window.Android || {
    input: function () {}, binary: function () {}, resized: function () {}, ready: function () {}, log: function () {}
  };
  var ESC = '\x1b';

  var term = new Terminal({
    fontSize: 13,
    fontFamily: 'ui-monospace, "Droid Sans Mono", "Noto Sans Mono", "DejaVu Sans Mono", monospace',
    cursorBlink: true,
    scrollback: 5000,
    theme: {
      background: '#0d1117', foreground: '#e6edf3', cursor: '#e6edf3', selectionBackground: '#264f78',
      black: '#484f58', red: '#ff7b72', green: '#3fb950', yellow: '#d29922', blue: '#58a6ff', magenta: '#bc8cff',
      cyan: '#39c5cf', white: '#b1bac4', brightBlack: '#6e7681', brightRed: '#ffa198', brightGreen: '#56d364',
      brightYellow: '#e3b341', brightBlue: '#79c0ff', brightMagenta: '#d2a8ff', brightCyan: '#56d4dd', brightWhite: '#ffffff'
    }
  });
  var fit = new FitAddon.FitAddon();
  term.loadAddon(fit);
  var el = document.getElementById('term');
  term.open(el);

  // ------------------------------------------------------------ input from the keyboard / xterm
  var ctrl = false, ctrlBtn = null;
  function setCtrl(v) { ctrl = v; if (ctrlBtn) ctrlBtn.classList.toggle('on', v); }
  function ctrlify(d) {
    if (d.length !== 1) return d;
    var c = d.charCodeAt(0);
    if (c >= 97 && c <= 122) return String.fromCharCode(c - 96);   // a-z
    if (c >= 64 && c <= 95) return String.fromCharCode(c - 64);    // @ A-Z [ \ ] ^ _
    if (d === ' ') return '\x00';
    if (d === '?') return '\x7f';
    return d;
  }
  term.onData(function (d) {
    if (ctrl) { d = ctrlify(d); setCtrl(false); }
    A.input(d);
  });
  term.onBinary(function (d) { A.binary(d); });

  function send(s) { A.input(s); }
  function csiOrSs3(ch) {
    if (ctrl) { setCtrl(false); return ESC + '[1;5' + ch; }
    return (term.modes.applicationCursorKeysMode ? ESC + 'O' : ESC + '[') + ch;
  }

  // ------------------------------------------------------------ extra keys
  function makeKey(row, label, fn, opts) {
    opts = opts || {};
    var b = document.createElement('div');
    b.className = 'k' + (opts.wide ? ' wide' : '') + (opts.big ? ' big' : '');
    b.textContent = label;
    var t1 = null, t2 = null;
    function stop() { clearTimeout(t1); clearInterval(t2); t1 = t2 = null; b.classList.remove('pressed'); }
    function fire(e) {
      e.preventDefault();          // keeps focus (and the soft keyboard) where it is
      stop();
      b.classList.add('pressed');
      fn();
      if (opts.repeat) t1 = setTimeout(function () { t2 = setInterval(fn, 70); }, 380);
    }
    b.addEventListener('touchstart', fire, { passive: false });
    b.addEventListener('touchend', stop);
    b.addEventListener('touchcancel', stop);
    b.addEventListener('mousedown', fire);     // desktop testing; suppressed on touch by preventDefault
    b.addEventListener('mouseup', stop);
    b.addEventListener('mouseleave', stop);
    row.appendChild(b);
    return b;
  }

  var row1 = document.getElementById('row1'), row2 = document.getElementById('row2');
  makeKey(row1, 'Esc', function () { send(ESC); });
  makeKey(row1, 'Tab', function () { send('\t'); });
  makeKey(row1, '⇧Tab', function () { send(ESC + '[Z'); });
  ctrlBtn = makeKey(row1, 'Ctrl', function () { setCtrl(!ctrl); });
  makeKey(row1, '↑', function () { send(csiOrSs3('A')); }, { repeat: true, big: true });
  makeKey(row1, '↓', function () { send(csiOrSs3('B')); }, { repeat: true, big: true });
  makeKey(row1, '←', function () { send(csiOrSs3('D')); }, { repeat: true, big: true });
  makeKey(row1, '→', function () { send(csiOrSs3('C')); }, { repeat: true, big: true });

  makeKey(row2, '⏎', function () { send('\r'); }, { wide: true, big: true });
  makeKey(row2, '^C', function () { send('\x03'); });
  makeKey(row2, 'PgUp', function () { send(ESC + '[5~'); }, { repeat: true });
  makeKey(row2, 'PgDn', function () { send(ESC + '[6~'); }, { repeat: true });
  makeKey(row2, 'Home', function () { send(csiOrSs3('H')); });
  makeKey(row2, 'End', function () { send(csiOrSs3('F')); });
  makeKey(row2, '/', function () { send('/'); });
  makeKey(row2, '⌫', function () { send('\x7f'); }, { repeat: true });

  // ------------------------------------------------------------ compose box
  var msg = document.getElementById('msg');
  function autoGrow() {
    msg.style.height = 'auto';
    msg.style.height = Math.min(140, msg.scrollHeight) + 'px';
    scheduleFit();
  }
  msg.addEventListener('input', autoGrow);
  var sendBtn = document.getElementById('send');
  function doSend() {
    var t = msg.value;
    if (t.length) {
      term.paste(t);               // bracketed paste when the app asked for it, so newlines stay newlines
      msg.value = '';
      autoGrow();
      setTimeout(function () { send('\r'); }, 80);
    } else {
      send('\r');
    }
  }
  sendBtn.addEventListener('touchstart', function (e) { e.preventDefault(); doSend(); }, { passive: false });
  sendBtn.addEventListener('mousedown', function (e) { e.preventDefault(); doSend(); });

  // ------------------------------------------------------------ swipe to scroll
  // Normal screen: scroll xterm's own scrollback. Alternate screen (tmux, full-screen apps):
  // synthesise wheel events, which xterm turns into mouse-wheel reports (tmux mouse mode) or arrow keys.
  var ty = null, tx = 0, acc = 0, moved = false;
  function cellH() { return Math.max(8, el.clientHeight / Math.max(1, term.rows)); }
  function scrollStep(dir) {
    var buf = term.buffer.active;
    if (term.modes.mouseTrackingMode === 'none' && buf.type === 'normal') { term.scrollLines(dir); return; }
    var target = term.element.querySelector('.xterm-screen') || term.element;
    var r = target.getBoundingClientRect();
    target.dispatchEvent(new WheelEvent('wheel', {
      deltaY: dir, deltaMode: 1, clientX: r.left + r.width / 2, clientY: Math.min(r.bottom - 2, Math.max(r.top + 2, ty || r.top + 2)),
      bubbles: true, cancelable: true
    }));
  }
  el.addEventListener('touchstart', function (e) {
    if (e.touches.length !== 1) { ty = null; return; }
    ty = e.touches[0].clientY; tx = e.touches[0].clientX; acc = 0; moved = false;
  }, { passive: true, capture: true });
  el.addEventListener('touchmove', function (e) {
    if (ty === null || e.touches.length !== 1) return;
    var y = e.touches[0].clientY;
    var dy = ty - y;                 // finger up => positive => towards newer output
    ty = y;
    acc += dy;
    if (!moved && (Math.abs(acc) > 10 || Math.abs(e.touches[0].clientX - tx) > 10)) moved = true;
    if (moved) {
      var h = cellH();
      while (Math.abs(acc) >= h) { var dir = acc > 0 ? 1 : -1; scrollStep(dir); acc -= dir * h; }
      e.preventDefault();
      e.stopPropagation();
    }
  }, { passive: false, capture: true });
  el.addEventListener('touchend', function (e) {
    if (moved) { e.preventDefault(); e.stopPropagation(); }
    else term.focus();               // a tap opens the keyboard on the terminal
    ty = null;
  }, { passive: false, capture: true });

  // ------------------------------------------------------------ sizing
  var fitT = null;
  function doFit() { try { fit.fit(); } catch (e) { A.log('fit: ' + e); } }
  function scheduleFit() { clearTimeout(fitT); fitT = setTimeout(doFit, 60); }
  window.addEventListener('resize', scheduleFit);
  if (window.ResizeObserver) new ResizeObserver(scheduleFit).observe(el);
  term.onResize(function (s) { A.resized(s.cols, s.rows); });

  // ------------------------------------------------------------ API used by Kotlin
  function b64ToBytes(b64) {
    var bin = atob(b64), u = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i);
    return u;
  }
  window.termWrite = function (b64) { term.write(b64ToBytes(b64)); };
  window.termReset = function () { term.reset(); };
  window.termSetFont = function (n) { term.options.fontSize = n; doFit(); A.resized(term.cols, term.rows); };
  window.termFocus = function () { term.focus(); };
  window.termFit = doFit;
  window.termSetCompose = function (show) { document.getElementById('compose').classList.toggle('hidden', !show); scheduleFit(); };
  window.termText = function () {
    var b = term.buffer.active, out = [];
    for (var i = 0; i < b.length; i++) { var l = b.getLine(i); if (l) out.push(l.translateToString(true)); }
    return out.join('\n').replace(/\s+$/, '');
  };
  window.__term = term;              // for debugging

  doFit();
  A.ready(term.cols, term.rows);
})();
