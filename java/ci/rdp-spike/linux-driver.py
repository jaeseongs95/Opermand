#!/usr/bin/env python3
"""ironrdp-agent 0.1.0 adoption spike, Linux runner (items 10 and 11 of the spike plan).

The 0.1.0 prebuilt CLI is much smaller than upstream master (no type-unicode, wait-frame, cert, daemon stop,
--no-prompt, RDP_PASSWORD). This driver tests what exists and records what is absent.

Environment: AGENT (path to ironrdp-agent), ART (artifact dir), MODE (nla|tls), RECV (receiver dir),
RDP_PW (test password, masked by the workflow), SERVER_CERT (path of the shadow server certificate, optional).
"""
import concurrent.futures, hashlib, json, os, re, signal, struct, subprocess, sys, tempfile, time

AGENT = os.environ["AGENT"]
ART = os.environ["ART"]
MODE = os.environ.get("MODE", "nla")
RECV = os.environ["RECV"]
PW = os.environ["RDP_PW"]
USER = "rdpuser"
SERVER = "127.0.0.1:3389"
os.makedirs(ART, exist_ok=True)
RESULTS = []
SOCK = "/tmp/ironrdp-spike-main.sock"
CTL = "/tmp/ironrdp-spike-ctl.sock"
PROPS = ["--prop", "desktopwidth:i:1280", "--prop", "desktopheight:i:800", "--prop", "ironrdp_autologon:i:1",
         "--prop", "compression:i:0", "--prop", "ironrdp_colordepth:i:16", "--prop", "ironrdp_serverpointer:i:0"]
UNIT = ("Hello, World! 0123456789 ~!@#$%^&*()_+-=[]{}|;':,./<>? \\ \"q\" "
        "한글 테스트 가나다 café €☃")
DAEMONS = {}


def scrub(t):
    return t.replace(PW, "***") if PW else t


def log(msg):
    line = "[%s] %s" % (time.strftime("%H:%M:%S"), scrub(msg))
    print(line, flush=True)
    with open(os.path.join(ART, "spike.log"), "a", encoding="utf-8") as f:
        f.write(line + "\n")


def result(item, check, verdict, detail=""):
    row = {"item": str(item), "check": "[mode=%s] %s" % (MODE, check), "verdict": verdict, "detail": scrub(detail)}
    RESULTS.append(row)
    with open(os.path.join(ART, "results.jsonl"), "a", encoding="utf-8") as f:
        f.write(json.dumps(row, ensure_ascii=False) + "\n")
    print("::notice title=RESULT item %s::%s => %s" % (item, row["check"], verdict), flush=True)
    log("RESULT item=%s check=%r verdict=%s detail=%s" % (item, row["check"], verdict, detail[:1500]))


def save(name, text):
    with open(os.path.join(ART, name), "w", encoding="utf-8") as f:
        f.write(scrub(text))


class R:
    def __init__(self, code, out, err, ms):
        self.code, self.out, self.err, self.ms = code, out, err, ms

    def __str__(self):
        s = "exit=%s ms=%s" % (self.code, self.ms)
        if self.out.strip():
            s += " stdout=" + self.out.strip()
        if self.err.strip():
            s += " stderr=" + self.err.strip()
        return s


def agent(args, env=None, timeout=60, endpoint=SOCK):
    """Run the CLI with output going to temp files (a child may inherit pipes and block a pipe-based read)."""
    cmd = [AGENT] + (["--endpoint", endpoint] if endpoint else []) + args
    e = dict(os.environ)
    if env:
        e.update(env)
    t0 = time.time()
    with tempfile.TemporaryFile() as fo, tempfile.TemporaryFile() as fe:
        p = subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=fo, stderr=fe, env=e)
        try:
            code = p.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            p.kill()
            p.wait()
            code = -999
        fo.seek(0)
        fe.seek(0)
        out = fo.read().decode("utf-8", "replace")
        err = fe.read().decode("utf-8", "replace")
    if code == -999:
        err += " timeout after %ss" % timeout
    return R(code, out, err, int((time.time() - t0) * 1000))


def status(endpoint=SOCK):
    r = agent(["status"], endpoint=endpoint, timeout=20)
    text = r.out + "\n" + r.err
    m = re.search(r"(?m)^state:\s*(\S+)", text)
    return {"code": r.code, "text": text, "state": m.group(1) if m else None}


def start_daemon(endpoint=SOCK, password=PW, tag="d"):
    """0.1.0 only has a foreground `daemon-start`; run it detached. The password goes in an overlay file."""
    argv = [AGENT, "--endpoint", endpoint, "daemon-start"]
    ov = None
    if password:
        ov = tempfile.NamedTemporaryFile("w", delete=False, suffix=".rdp")
        os.chmod(ov.name, 0o600)
        ov.write("username:s:%s\nClearTextPassword:s:%s\n" % (USER, password))
        ov.close()
        argv += ["--overlay", ov.name]
    base = os.path.join(ART, "daemon-%s-%s" % (os.path.basename(endpoint), tag))
    fo, fe = open(base + ".out.txt", "w"), open(base + ".err.txt", "w")
    p = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=fo, stderr=fe, start_new_session=True)
    DAEMONS[endpoint] = (p, ov.name if ov else None, base)
    t0 = time.time()
    while time.time() - t0 < 30 and p.poll() is None:
        time.sleep(0.3)
        if agent(["status"], endpoint=endpoint, timeout=10).code == 0:
            return True, p.pid
    return False, p.pid


def stop_daemon(endpoint=SOCK):
    d = DAEMONS.pop(endpoint, None)
    if not d:
        return
    p, ov, base = d
    try:
        p.kill()
        p.wait(timeout=5)
    except Exception:
        pass
    if ov and os.path.exists(ov):
        os.remove(ov)
    for suf in (".out.txt", ".err.txt"):
        try:
            t = open(base + suf).read()
            open(base + suf, "w").write(scrub(t))
        except Exception:
            pass
    try:
        os.remove(endpoint)  # a killed daemon leaves its socket file behind
    except Exception:
        pass


def wait_connected(label, timeout=90, endpoint=SOCK):
    t0 = time.time()
    seen = []
    last = None
    s = {"code": None, "text": "", "state": None}
    while time.time() - t0 < timeout:
        s = status(endpoint)
        key = (s["code"], s["state"])
        if key != last:
            seen.append("[%s] %s" % (time.strftime("%H:%M:%S"), s["text"].strip()))
            last = key
        if s["state"] in ("Connected", "Failed"):
            break
        time.sleep(0.5)
    save("status-%s.txt" % label, "\n---\n".join(seen))
    return s


def connect(extra=(), props=PROPS, endpoint=SOCK, server=SERVER):
    return agent(["connect", "--server", server, "-u", USER] + list(props) + list(extra), endpoint=endpoint, timeout=90)


def rjson(name):
    p = os.path.join(RECV, name)
    for _ in range(5):
        try:
            with open(p, "rb") as f:
                return json.loads(f.read().decode())
        except Exception:
            time.sleep(0.05)
    return None


def rbytes():
    try:
        with open(os.path.join(RECV, "text.bin"), "rb") as f:
            return f.read()
    except FileNotFoundError:
        return b""


def reset_recv():
    ack = rjson("reset.ack")
    n = ack["n"] if ack else 0
    with open(os.path.join(RECV, "reset.flag"), "w") as f:
        f.write("x")
    t0 = time.time()
    while time.time() - t0 < 10:
        time.sleep(0.1)
        a = rjson("reset.ack")
        if a and a["n"] > n:
            return True
    return False


def wait_stable(expected=None, quiet=2.5, maxsec=40):
    t0 = time.time()
    last = rbytes()
    lc = time.time()
    while time.time() - t0 < maxsec:
        time.sleep(0.1)
        cur = rbytes()
        if cur != last:
            last, lc = cur, time.time()
        elif time.time() - lc >= quiet:
            break
        if expected is not None and cur == expected and time.time() - lc >= 0.8:
            break
    return last, int((lc - t0) * 1000)


def compare(expected, actual):
    eb = expected.encode("utf-8")
    d = "expectedBytes=%d actualBytes=%d expectedSha256=%s actualSha256=%s" % (
        len(eb), len(actual), hashlib.sha256(eb).hexdigest(), hashlib.sha256(actual).hexdigest())
    if eb != actual:
        a = actual.decode("utf-8", "replace")
        idx = next((i for i, (x, y) in enumerate(zip(expected, a)) if x != y), min(len(expected), len(a)))
        if sorted(expected) == sorted(a):
            kind = "REORDERED"
        elif len(a) < len(expected):
            kind = "MISSING-or-changed"
        elif len(a) > len(expected):
            kind = "EXTRA-or-changed"
        else:
            kind = "content-differs"
        d += " firstDiffIndex=%d kind=%s expectedAround=%r actualAround=%r" % (
            idx, kind, expected[max(0, idx - 10):idx + 30], a[max(0, idx - 10):idx + 30])
    return eb == actual, d


def png_info(path):
    b = open(path, "rb").read()
    if b[:8] != b"\x89PNG\r\n\x1a\n":
        return False, "bad signature", None
    w, h = struct.unpack(">II", b[16:24])
    try:
        from PIL import Image
        im = Image.open(path).convert("RGB")
        colors = im.getcolors(maxcolors=1 << 24)
        n = len(colors) if colors else 1 << 24
        dom = max(c for c, _ in colors) / (w * h) if colors else 0
        return (w > 0 and h > 0 and n > 8), "bytes=%d IHDR=%dx%d distinctColors=%d dominantColorShare=%.4f" % (
            len(b), w, h, n, dom), (w, h)
    except Exception as ex:
        return False, "decode failed: %r" % ex, (w, h)


def focus_input():
    st = rjson("state.json")
    if st and st["focused"] == "input":
        return "focused already"
    lay = rjson("ready.json")
    agent(["mouse-move", "--x", str(lay["input"]["x"]), "--y", str(lay["input"]["y"])])
    agent(["mouse-button", "--button", "left", "--pressed", "true"])
    agent(["mouse-button", "--button", "left", "--pressed", "false"])
    time.sleep(0.6)
    return "clicked input to focus; focused=%s" % (rjson("state.json") or {}).get("focused")


def type_case(text):
    """key-unicode, one Unicode scalar per press+release (the only text-entry command in 0.1.0)."""
    reset_recv()
    f = focus_input()
    bad = []
    t0 = time.time()
    for i, ch in enumerate(text):  # Python iterates by code point
        d = agent(["key-unicode", "--char", ch, "--pressed", "true"], timeout=30)
        u = agent(["key-unicode", "--char", ch, "--pressed", "false"], timeout=30)
        if d.code != 0 or u.code != 0:
            if len(bad) < 5:
                bad.append("char#%d %r down:%s up:%s" % (i, ch, d, u))
    send_ms = int((time.time() - t0) * 1000)
    got, settle = wait_stable(text.encode("utf-8"))
    ok, d = compare(text, got)
    return ok, "scalars=%d cliCalls=%d sendMs=%d settleMs=%d cliFailures=%d [%s] focus=%r %s" % (
        len(text), len(text) * 2, send_ms, settle, len(bad), "; ".join(bad), f, d)


def agent_lines():
    ps = subprocess.run(["ps", "-eo", "pid,ppid,args"], capture_output=True, text=True).stdout
    return [l for l in ps.splitlines() if "ironrdp-agent" in l and "ps -eo" not in l]


def main():
    nla = MODE == "nla"
    up, pid = start_daemon()
    result(9, "daemon-start (foreground process, detached by the harness) with --overlay answers status", "pass" if up else "fail", "pid=%s" % pid)
    c = connect()
    save("connect1.txt", str(c))
    result(2, "connect command output / exit code", "info", str(c))
    s = wait_connected("c1", 90)
    ok = s["state"] == "Connected"
    result(11, "ironrdp-agent connects to the Linux RDP server (%s) with the overlay password" % (
        "NLA required" if nla else "TLS only, no auth"), "pass" if ok else "fail", "state=%s status: %s" % (s["state"], s["text"].strip()))
    result(6, "strict TLS validation by default: a self-signed server certificate must be refused without a pin",
           "fail" if ok else "info", "connected with no pin and no trust store: %s (source: rustls NoCertificateVerification at the 0.1.0 tag)" % ok)
    lg = agent(["query-logs"])
    save("session-log.txt", lg.out + lg.err)
    lines = [l for l in lg.out.splitlines() if re.search(r"(?i)credssp|nla|hybrid|ntlm|security protocol|selected protocol|certificate|tls", l)][:14]
    result(11, "session log lines about the security protocol / certificate", "info", " || ".join(lines))
    if not ok:
        return
    start_daemon(CTL, tag="ctl")
    connect(extra=["--prop", "enablecredsspsupport:i:0"], endpoint=CTL)
    sn = wait_connected("ctl-no-credssp", 30, CTL)
    if nla:
        result(11, "control: client with CredSSP disabled is refused by the NLA-only server (proves NLA is enforced and used)",
               "pass" if sn["state"] != "Connected" else "fail", "state=%s status: %s" % (sn["state"], sn["text"].strip()))
    else:
        result(11, "control (TLS-only server): client with CredSSP disabled", "info", "state=%s" % sn["state"])
    agent(["disconnect"], endpoint=CTL)
    stop_daemon(CTL)
    start_daemon(CTL, password="Wrong-Passw0rd!x", tag="wrongpw")
    connect(endpoint=CTL)
    sw = wait_connected("ctl-wrong-password", 30, CTL)
    result(2, "wrong password: connection fails and the status says so" if nla else "wrong password against the no-auth TLS server", "pass" if sw["state"] != "Connected" else ("fail" if nla else "info"),
           "state=%s status: %s" % (sw["state"], sw["text"].strip()))
    stop_daemon(CTL)

    t0 = time.time()
    lay = None
    while time.time() - t0 < 30 and not lay:
        lay = rjson("ready.json")
        time.sleep(0.5)
    if not lay:
        result(11, "receiver app on the X display", "fail", "receiver did not start")
        return
    time.sleep(2)
    p1 = os.path.join(ART, "screenshot-main-1.png")
    r = agent(["screenshot", p1])
    okp, txt, wh = png_info(p1)
    result(11, "screenshot writes a valid, non-uniform PNG", "pass" if r.code == 0 and okp else "fail", "%s | %s" % (r, txt))

    agents = agent_lines()
    save("agent-processes.txt", "\n".join(agents))
    leak = [l for l in agents if PW in l]
    result(7, "overlay path: password absent from the command line of the live ironrdp-agent daemon process (Linux)",
           "pass" if agents and not leak else "fail", "processes=%d leaks=%d cmdlines: %s" % (len(agents), len(leak), " || ".join(scrub(a) for a in agents)))
    environs = []
    for l in agents:
        pid_ = l.split()[0]
        try:
            env = open("/proc/%s/environ" % pid_, "rb").read().decode(errors="replace")
            environs.append("pid=%s environHasPassword=%s" % (pid_, PW in env))
        except Exception as ex:
            environs.append("pid=%s environ unreadable %r" % (pid_, ex))
    result(7, "daemon process environment does not carry the password (read from /proc)", "info", "; ".join(environs))
    props = agent(["query-props"])
    result(7, "query-props / query-logs output does not echo the password", "pass" if PW not in props.out + props.err + lg.out else "fail", "checked")
    result(7, "status reports credentials loaded from the overlay", "info", str(agent(["status"])))
    r_ = agent(["connect", "--server", SERVER, "-u", USER, "-p", PW])
    # observed through ps while it runs is racy; by definition -p is argv (clap), record exit info only
    result(7, "argv path: connect -p PASSWORD (by definition the password is in the CLI argv)", "info", str(r_))

    ok1, d = type_case(UNIT)
    result(4, "key-unicode: mixed ASCII/Hangul/symbols string (<=96 chars), one char per call, byte-exact at target", "pass" if ok1 else "fail", d)
    long = "".join("[%04d] %s " % (i, UNIT) for i in range(1, 7))
    okc = 0
    fails = []
    for it in range(2):
        ok1, d = type_case(long)
        okc += 1 if ok1 else 0
        if not ok1:
            fails.append("iter%d: %s" % (it + 1, d))
    result(4, "key-unicode: ~%d-char mixed text (beyond 96), 2 repetitions, byte-exact" % len(long), "pass" if okc == 2 else "fail",
           "passed %d/2. %s" % (okc, " ### ".join(fails)))
    ok1, d = type_case("A\U0001F600B")
    result(4, "key-unicode: character outside the BMP (U+1F600)", "info", "equal=%s received=%r %s" % (ok1, rbytes(), d))
    ok1, d = type_case("line1\nline2")
    result(4, "key-unicode: embedded LF", "info", "equal=%s received=%r %s" % (ok1, rbytes(), d))
    ok1, d = type_case("a\tb")
    result(4, "key-unicode: embedded TAB", "info", "equal=%s received=%r %s" % (ok1, rbytes(), d))
    p2 = os.path.join(ART, "screenshot-main-2.png")
    agent(["screenshot", p2])
    try:
        from PIL import Image, ImageChops
        diff = ImageChops.difference(Image.open(p1).convert("RGB"), Image.open(p2).convert("RGB")).getbbox()
        result(3, "screenshot reflects screen changes after input", "pass" if diff else "fail", "diff bbox=%s" % (diff,))
    except Exception as ex:
        result(3, "screenshot reflects screen changes after input", "unavailable", repr(ex))

    def key(code):
        agent(["key-scancode", "--scancode", code, "--pressed", "true"])
        agent(["key-scancode", "--scancode", code, "--pressed", "false"])

    def cur():
        time.sleep(0.7)
        return rbytes().decode("utf-8", "replace")

    reset_recv()
    focus_input()
    key("0x1E")
    s1 = cur()
    agent(["key-scancode", "--scancode", "0x2A", "--pressed", "true"])
    key("0x1E")
    agent(["key-scancode", "--scancode", "0x2A", "--pressed", "false"])
    s2 = cur()
    key("0x1C")
    key("0x30")
    s3 = cur()
    key("0x0E")
    s4 = cur()
    agent(["key-scancode", "--scancode", "0x1D", "--pressed", "true"])
    key("0x1E")
    agent(["key-scancode", "--scancode", "0x1D", "--pressed", "false"])
    key("0x0E")
    s5 = cur()
    okk = (s1 == "a" and s2 == "aA" and s3 == "aA\nb" and s4 == "aA\n" and s5 == "")
    result(4, "key-scancode: letter, Shift+letter, Enter, Backspace, Ctrl+A then Backspace observed at target",
           "pass" if okk else "fail", "a=%r Shift+a=%r Enter,b=%r Backspace=%r Ctrl+A,Backspace=%r" % (s1, s2, s3, s4, s5))
    agent(["mouse-move", "--x", "321", "--y", "123"])
    time.sleep(0.5)
    st = rjson("state.json")
    result(4, "mouse-move: pointer at target equals request (321,123)", "pass" if st["cursor"] == {"x": 321, "y": 123} else "fail", "target=%s" % st["cursor"])
    agent(["mouse-move", "--x", "1279", "--y", "799"])
    time.sleep(0.5)
    st = rjson("state.json")
    result(4, "mouse-move: far corner (1279,799)", "pass" if st["cursor"] == {"x": 1279, "y": 799} else "fail", "target=%s" % st["cursor"])
    evp = os.path.join(RECV, "events.log")
    before = len(open(evp).read().splitlines()) if os.path.exists(evp) else 0
    agent(["mouse-move", "--x", str(lay["button"]["x"]), "--y", str(lay["button"]["y"])])
    agent(["mouse-button", "--button", "left", "--pressed", "true"])
    agent(["mouse-button", "--button", "left", "--pressed", "false"])
    agent(["mouse-move", "--x", str(lay["panel"]["x"]), "--y", str(lay["panel"]["y"])])
    for b in ("right", "middle"):
        agent(["mouse-button", "--button", b, "--pressed", "true"])
        agent(["mouse-button", "--button", b, "--pressed", "false"])
    time.sleep(1)
    ev = open(evp).read().splitlines()[before:]
    click = any("|Click|btnA" in e for e in ev)
    rd = any("|MouseDown|panel|3|" in e for e in ev)
    md = any("|MouseDown|panel|2|" in e for e in ev)
    result(4, "mouse-button left: click on the target button registered", "pass" if click else "fail",
           " ; ".join(e for e in ev if re.search("Click|MouseDown", e)))
    result(4, "mouse-button right/middle registered at target", "pass" if rd and md else "fail", "right=%s middle=%s" % (rd, md))
    agent(["mouse-move", "--x", str(lay["scroll"]["x"]), "--y", str(lay["scroll"]["y"])])
    l0 = rjson("state.json")["firstVisibleLine"]
    before = len(open(evp).read().splitlines())
    w = agent(["wheel", "--delta", "-120"])
    agent(["wheel", "--delta", "-120"])
    agent(["wheel", "--delta", "-120"])
    time.sleep(1)
    l1 = rjson("state.json")["firstVisibleLine"]
    ev = open(evp).read().splitlines()[before:]
    result(4, "wheel -120 x3 scrolls the target text down", "pass" if l1 > l0 else "fail",
           "firstVisibleLine %s -> %s events=%s %s" % (l0, l1, [e for e in ev if "Wheel" in e], w))
    agent(["wheel", "--delta", "120"])
    time.sleep(0.6)
    l2 = rjson("state.json")["firstVisibleLine"]
    result(4, "wheel +120 scrolls back up", "pass" if l2 < l1 else "fail", "firstVisibleLine %s -> %s" % (l1, l2))

    reset_recv()
    focus_input()
    r = agent(["key-unicode", "--char", "Q", "--pressed", "true"])
    r2 = agent(["key-unicode", "--char", "Q", "--pressed", "false"])
    t_ret = time.time()
    t_eff = None
    while time.time() - t_ret < 5:
        if len(rbytes()) >= 1:
            t_eff = time.time()
            break
        time.sleep(0.005)
    result(5, "key-unicode success output and exit code", "info", "%s || %s" % (r, r2))
    result(5, "time between CLI return and target seeing the character", "info",
           "cliMs=%d+%d returnToSeenMs=%s" % (r.ms, r2.ms, int((t_eff - t_ret) * 1000) if t_eff else "not seen in 5 s"))
    for cmd in (["mouse-move", "--x", "10", "--y", "10"], ["wheel", "--delta", "120"], ["status"]):
        result(5, "output of '%s'" % " ".join(cmd), "info", str(agent(cmd)))
    result(5, "invalid input is rejected with a message (key-unicode --char with two characters)", "info", str(agent(["key-unicode", "--char", "ab", "--pressed", "true"])))
    reset_recv()
    focus_input()

    def one(i):
        ch = chr(97 + i % 26)
        d = agent(["key-unicode", "--char", ch, "--pressed", "true"], timeout=60)
        u = agent(["key-unicode", "--char", ch, "--pressed", "false"], timeout=60)
        return ch, d, u

    with concurrent.futures.ThreadPoolExecutor(max_workers=50) as ex:
        rs = list(ex.map(one, range(1, 201)))
    acc = [x for x in rs if x[1].code == 0 and x[2].code == 0]
    rej = [x for x in rs if not (x[1].code == 0 and x[2].code == 0)]
    got, _ = wait_stable(None, quiet=4, maxsec=120)
    g = got.decode("utf-8", "replace")
    diff = []
    for c in range(97, 123):
        ch = chr(c)
        e = sum(1 for x in acc if x[0] == ch)
        a = g.count(ch)
        if e != a:
            diff.append("%s expected=%d got=%d" % (ch, e, a))
    msgs = {}
    for x in rej:
        k = ((x[1].err or x[1].out) + (x[2].err or x[2].out)).strip()[:200]
        msgs[k] = msgs.get(k, 0) + 1
    result(5, "parallel flood: 200 concurrent key-unicode pairs; accepted=%d rejected=%d" % (len(acc), len(rej)), "info", "rejection messages: %s" % msgs)
    result(5, "every call reported as success was delivered (per-letter counts equal; nothing extra)",
           "pass" if not diff and len(g) == len(acc) else "fail", "accepted=%d receivedLen=%d mismatches=%s" % (len(acc), len(g), diff))

    d = agent(["disconnect"])
    ds = agent(["status"])
    result(9, "disconnect output and status afterwards (daemon expected to stay up)", "info", "%s | %s" % (d, ds))
    stop_daemon()
    time.sleep(2)
    left = agent_lines()
    result(9, "after disconnect + killing the daemon: no ironrdp-agent process left", "pass" if not left else "fail", "processes=%s" % left)
    # killed daemon while connected, then reuse of the same endpoint path
    start_daemon(tag="r2")
    connect()
    wait_connected("kill-connect", 60)
    p_, ov_, base_ = DAEMONS[SOCK]
    p_.kill()
    p_.wait()
    time.sleep(2)
    stt = agent(["status"])
    sock_left = os.path.exists(SOCK)
    result(9, "daemon SIGKILLed while connected: status afterwards and what is left", "info", "status: %s | stale socket file still present=%s" % (stt, sock_left))
    ok2, pid2 = start_daemon(tag="r3")
    connect()
    s2 = wait_connected("kill-reconnect", 60)
    result(9, "after SIGKILL a new daemon on the same endpoint path starts and connects (stale socket handled)",
           "pass" if ok2 and s2["state"] == "Connected" else "fail", "daemon up=%s state=%s" % (ok2, s2["state"]))
    agent(["disconnect"])
    stop_daemon()


try:
    main()
except Exception:  # keep artifacts even if the harness breaks
    import traceback
    result("harness", "driver exception", "fail", traceback.format_exc())
finally:
    for ep in list(DAEMONS):
        stop_daemon(ep)
json.dump(RESULTS, open(os.path.join(ART, "results.json"), "w"), ensure_ascii=False, indent=1)
