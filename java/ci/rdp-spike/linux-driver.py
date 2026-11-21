#!/usr/bin/env python3
"""ironrdp-agent adoption spike, Linux runner (items 10 and 11 of the spike plan).

Environment: AGENT (path to ironrdp-agent), ART (artifact dir), MODE (nla|tls), RECV (receiver dir),
RDP_PW (test password, masked by the workflow), SERVER_CERT (path of the shadow server certificate, optional).
"""
import concurrent.futures, hashlib, json, os, re, signal, struct, subprocess, sys, time

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
    cmd = [AGENT] + (["--endpoint", endpoint] if endpoint else []) + args
    e = dict(os.environ)
    if env:
        e.update(env)
    t0 = time.time()
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", env=e, timeout=timeout,
                           stdin=subprocess.DEVNULL)
        return R(p.returncode, p.stdout, p.stderr, int((time.time() - t0) * 1000))
    except subprocess.TimeoutExpired:
        return R(-999, "", "timeout after %ss" % timeout, int((time.time() - t0) * 1000))


def status(endpoint=SOCK):
    r = agent(["status"], endpoint=endpoint, timeout=20)
    text = r.out + "\n" + r.err
    m = re.search(r"(?m)^state:\s*(\S+)", text)
    return {"code": r.code, "text": text, "state": m.group(1) if m else None}


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
    return agent(["connect", "--no-prompt", "--auto-start", "--server", server] + list(props) + list(extra),
                 env={"RDP_USERNAME": USER, "RDP_PASSWORD": PW}, endpoint=endpoint)


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


def chunks(text, n=96):
    return [text[i:i + n] for i in range(0, len(text), n)]  # Python str indexes by code point


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


def type_case(text, chunk=96):
    reset_recv()
    f = focus_input()
    bad = []
    t0 = time.time()
    cs = chunks(text, chunk)
    for i, c in enumerate(cs):
        r = agent(["type-unicode", "--text", c], timeout=30)
        if r.code != 0:
            bad.append("chunk%d %s" % (i, r))
    send_ms = int((time.time() - t0) * 1000)
    got, settle = wait_stable(text.encode("utf-8"))
    ok, d = compare(text, got)
    return ok, "chunks=%d sendMs=%d settleMs=%d cliFailures=%d [%s] focus=%r %s" % (
        len(cs), send_ms, settle, len(bad), "; ".join(bad), f, d)


def agent_lines():
    ps = subprocess.run(["ps", "-eo", "pid,ppid,args"], capture_output=True, text=True).stdout
    return [l for l in ps.splitlines() if "ironrdp-agent" in l and "ps -eo" not in l]


def main():
    nla = MODE == "nla"
    r = agent(["daemon", "start"])
    save("daemon-start.txt", str(r))
    result(9, "daemon start --endpoint PATH (background)", "pass" if r.code == 0 else "fail", str(r))
    c = connect()
    save("connect1.txt", str(c))
    s = wait_connected("c1-strict-nopin", 60)
    m = re.search(r"cert trust\s+(\S+)\s+([0-9A-Fa-f:]{64,95})", s["text"])
    srvfp = None
    cert = os.environ.get("SERVER_CERT")
    if cert and os.path.exists(cert):
        out = subprocess.run(["openssl", "x509", "-in", cert, "-noout", "-fingerprint", "-sha256"],
                             capture_output=True, text=True).stdout
        srvfp = out.split("=", 1)[-1].strip().replace(":", "").lower()
    if s["state"] == "Connected":
        result(6, "strict TLS (default) with self-signed server cert, no pin", "info", "connected without pin: " + s["text"].strip())
    else:
        eq = bool(m and srvfp and m.group(2).replace(":", "").lower() == srvfp)
        result(6, "strict TLS (default): self-signed cert rejected and status reports a fingerprint",
               "pass" if m else "fail",
               "state=%s reported=%s serverCertSha256(openssl)=%s equal=%s status: %s" % (
                   s["state"], m.group(2) if m else None, srvfp, eq, s["text"].strip()))
        if m:
            t = agent(["cert", "trust", m.group(1), m.group(2)], endpoint=None)
            result(6, "cert trust HOST:PORT SHA256", "pass" if t.code == 0 else "fail", str(t))
            connect()
            s = wait_connected("c2-strict-pinned", 90)
    ok = s["state"] == "Connected"
    result(11, "ironrdp-agent connects to the Linux RDP server (%s) with strict TLS + pin" % (
        "NLA required" if nla else "TLS only, no auth"), "pass" if ok else "fail",
        "state=%s status: %s" % (s["state"], s["text"].strip()))
    lg = agent(["log"])
    save("session-log.txt", lg.out + lg.err)
    lines = [l for l in lg.out.splitlines() if re.search(r"(?i)credssp|nla|hybrid|ntlm|security protocol|selected protocol", l)][:12]
    result(11, "session log lines about the security protocol", "info", " || ".join(lines))
    if not ok:
        return
    agent(["daemon", "start", "--skip-certificate-check"], endpoint=CTL)
    connect(extra=["--prop", "enablecredsspsupport:i:0"], endpoint=CTL)
    sn = wait_connected("ctl-no-credssp", 30, CTL)
    if nla:
        result(11, "control: client without CredSSP is refused by the NLA-only server (proves NLA is enforced)",
               "pass" if sn["state"] != "Connected" else "fail", "state=%s status: %s" % (sn["state"], sn["text"].strip()))
    else:
        result(11, "control (TLS-only server): client without CredSSP", "info", "state=%s" % sn["state"])
    agent(["disconnect"], endpoint=CTL)
    agent(["daemon", "stop"], endpoint=CTL)

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
    leak = [l for l in agents if PW in l or re.search(r"--password|\s-p\s", l)]
    result(7, "password absent from the command line of every ironrdp-agent process (env-var path), Linux",
           "pass" if agents and not leak else "fail",
           "processes=%d leaks=%d cmdlines: %s" % (len(agents), len(leak), " || ".join(scrub(a) for a in agents)))
    environs = []
    for l in agents:
        pid = l.split()[0]
        try:
            env = open("/proc/%s/environ" % pid, "rb").read().decode(errors="replace")
            environs.append("pid=%s environHasPassword=%s environHasRDP_PASSWORD=%s" % (pid, PW in env, "RDP_PASSWORD" in env))
        except Exception as ex:
            environs.append("pid=%s environ unreadable %r" % (pid, ex))
    result(7, "agent/daemon process environment does not carry the password (read from /proc)", "info", "; ".join(environs))

    ok1, d = type_case(UNIT)
    result(4, "type-unicode: mixed ASCII/Hangul/symbols, <=96 chars, byte-exact at target", "pass" if ok1 else "fail", d)
    pad = UNIT
    while len(pad) < 96:
        pad += "x"
    ok1, d = type_case(pad)
    result(4, "type-unicode: exactly 96 chars byte-exact", "pass" if ok1 else "fail", d)
    reset_recv()
    r97 = agent(["type-unicode", "--text", pad + "y"])
    time.sleep(2)
    result(4, "97 chars in one call is rejected and nothing delivered", "pass" if r97.code != 0 and len(rbytes()) == 0 else "fail",
           "%s receiverBytes=%d" % (r97, len(rbytes())))

    def mk(n):
        return "".join("[%04d] %s " % (i, UNIT) for i in range(1, n + 1))

    for label, n, reps in (("~1100", 10, 5), ("~4400", 40, 2)):
        okc = 0
        fails = []
        for it in range(reps):
            ok1, d = type_case(mk(n))
            okc += 1 if ok1 else 0
            if not ok1:
                fails.append("iter%d: %s" % (it + 1, d))
        result(4, "type-unicode: %s-char mixed text as sequential <=96 calls, %d repetitions, byte-exact" % (label, reps),
               "pass" if okc == reps else "fail", "passed %d/%d. %s" % (okc, reps, " ### ".join(fails)))
    ok1, d = type_case(UNIT, 1)
    result(4, "type-unicode: one character per call preserves order", "pass" if ok1 else "fail", d)
    ok1, d = type_case("A\U0001F600B")
    result(4, "type-unicode: character outside the BMP (U+1F600)", "info", "equal=%s received=%r %s" % (ok1, rbytes(), d))
    ok1, d = type_case("line1\nline2")
    result(4, "type-unicode: embedded LF", "info", "equal=%s received=%r %s" % (ok1, rbytes(), d))
    ok1, d = type_case("a\tb")
    result(4, "type-unicode: embedded TAB", "info", "equal=%s received=%r %s" % (ok1, rbytes(), d))
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
           "pass" if okk else "fail",
           "a=%r Shift+a=%r Enter,b=%r Backspace=%r Ctrl+A,Backspace=%r" % (s1, s2, s3, s4, s5))
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
    r = agent(["type-unicode", "--text", "ACKTEST"])
    t_ret = time.time()
    t_eff = None
    while time.time() - t_ret < 5:
        if len(rbytes()) >= 7:
            t_eff = time.time()
            break
        time.sleep(0.005)
    result(5, "type-unicode success output / exit code", "info", str(r))
    result(5, "time between CLI return and target seeing the text", "info",
           "cliMs=%d returnToSeenMs=%s" % (r.ms, int((t_eff - t_ret) * 1000) if t_eff else "not seen in 5 s"))
    for cmd in (["mouse-move", "--x", "10", "--y", "10"], ["wheel", "--delta", "120"],
                ["wait-frame", "--after-frame", "0", "--timeout-ms", "3000"], ["status"]):
        result(5, "output of '%s'" % " ".join(cmd), "info", str(agent(cmd)))
    reset_recv()
    focus_input()

    def one(i):
        t = "[%03d:%s]" % (i, chr(97 + i % 26) * 80)
        return i, t, agent(["type-unicode", "--text", t], timeout=60)

    with concurrent.futures.ThreadPoolExecutor(max_workers=50) as ex:
        rs = list(ex.map(one, range(1, 151)))
    acc = [x for x in rs if x[2].code == 0]
    rej = [x for x in rs if x[2].code != 0]
    got, _ = wait_stable(None, quiet=4, maxsec=120)
    g = got.decode("utf-8", "replace")
    pat = r"\[(\d{3}):([a-z])\2{79}\]"
    toks = [int(m.group(1)) for m in re.finditer(pat, g)]
    accids = [x[0] for x in acc]
    missing = [i for i in accids if i not in toks]
    extra = [i for i in toks if i not in accids]
    residue = re.sub(pat, "", g)
    msgs = {}
    for x in rej:
        k = (x[2].err.strip() or x[2].out.strip())[:200]
        msgs[k] = msgs.get(k, 0) + 1
    result(5, "parallel flood: 150 concurrent type-unicode calls; accepted=%d rejected=%d" % (len(acc), len(rej)), "info", "rejection messages: %s" % msgs)
    result(5, "every call reported as success was delivered intact; no rejected call leaked partial text",
           "pass" if not missing and not extra and not residue else "fail",
           "accepted=%d deliveredTokens=%d missingAccepted=%s extraRejected=%s residueChars=%d" % (len(acc), len(toks), missing, extra, len(residue)))

    d = agent(["disconnect"])
    ds = agent(["daemon", "status"])
    result(9, "disconnect output and daemon status afterwards", "info", "%s | %s" % (d, ds))
    st_ = agent(["daemon", "stop"])
    time.sleep(2)
    left = agent_lines()
    socks = [p for p in os.listdir("/tmp") if p.startswith("ironrdp-spike")]
    result(9, "after disconnect + daemon stop: no ironrdp-agent process and no socket file left",
           "pass" if not left and not socks else "fail", "stop: %s | processes=%s sockets=%s" % (st_, left, socks))
    agent(["daemon", "start"])
    connect()
    wait_connected("kill-connect", 60)
    ag = [l.split()[0] for l in agent_lines() if "daemon" in l]
    for p in ag:
        try:
            os.kill(int(p), signal.SIGKILL)
        except Exception:
            pass
    time.sleep(2)
    stt = agent(["status"])
    socks = [p for p in os.listdir("/tmp") if p.startswith("ironrdp-spike")]
    result(9, "daemon SIGKILLed while connected: status afterwards and what is left", "info",
           "killed=%s status: %s | leftover sockets=%s" % (ag, stt, socks))
    r2 = agent(["daemon", "start"])
    connect()
    s2 = wait_connected("kill-reconnect", 60)
    result(9, "after SIGKILL a new daemon on the same endpoint path connects again (stale socket handled)",
           "pass" if s2["state"] == "Connected" else "fail", "daemon start: %s state=%s" % (r2, s2["state"]))
    agent(["disconnect"])
    agent(["daemon", "stop"])


try:
    main()
except Exception as ex:  # keep artifacts even if the harness breaks
    import traceback
    result("harness", "driver exception", "fail", traceback.format_exc())
json.dump(RESULTS, open(os.path.join(ART, "results.json"), "w"), ensure_ascii=False, indent=1)
