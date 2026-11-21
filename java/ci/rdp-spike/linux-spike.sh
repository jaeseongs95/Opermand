#!/usr/bin/env bash
# ironrdp-agent adoption spike, Linux runner: set up an RDP server (FreeRDP shadow + Xvfb) and run the driver.
# Env: MODE (nla|tls), ART, WORK, RELEASE_BASE, ASSET, PINNED_SHA256 ; script dir holds linux-driver.py / linux-receiver.py.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
MODE="${MODE:-nla}"; ART="${ART:?}"; WORK="${WORK:?}"
mkdir -p "$ART" "$WORK"; LOG="$ART/setup.log"; : > "$LOG"
say() { echo "[$(date +%T)] $*" | tee -a "$LOG"; }
res() { # item check verdict detail
  python3 - "$1" "$2" "$3" "$4" "$MODE" >> "$ART/results.jsonl" <<'PY'
import json, sys
i, c, v, d, m = sys.argv[1:6]
print(json.dumps({"item": i, "check": "[mode=%s] %s" % (m, c), "verdict": v, "detail": d}, ensure_ascii=False))
PY
  echo "::notice title=RESULT item $1::$2 => $3"
  say "RESULT item=$1 check='$2' verdict=$3 detail=$4"
}

# ---- item 10: download, verify, run ----
cd "$WORK"
curl -fsSL -o "$ASSET" "$RELEASE_BASE/$ASSET" && curl -fsSL -o "$ASSET.sha256" "$RELEASE_BASE/$ASSET.sha256"
ACT=$(sha256sum "$ASSET" | cut -d' ' -f1); SIDE=$(cut -d' ' -f1 < "$ASSET.sha256")
if [ "$ACT" != "$PINNED_SHA256" ] || [ "$SIDE" != "$PINNED_SHA256" ]; then
  res 12 "linux archive SHA-256 equals pinned value and sidecar" fail "pinned=$PINNED_SHA256 sidecar=$SIDE actual=$ACT"; exit 1
fi
res 12 "linux archive SHA-256 equals pinned value and sidecar" pass "sha256=$ACT size=$(stat -c %s "$ASSET")"
mkdir -p agent && tar -xzf "$ASSET" -C agent && tar -tzvf "$ASSET" > "$ART/archive-listing.txt"
AGENT="$(find "$WORK/agent" -type f -name ironrdp-agent | head -1)"; chmod +x "$AGENT"
{
  echo "os: $(. /etc/os-release; echo "$PRETTY_NAME")"; echo "glibc: $(ldd --version | head -1)"; echo "kernel: $(uname -r)"
  echo "--- file"; file "$AGENT"; ls -l "$AGENT"
  echo "--- ldd"; ldd "$AGENT"
  echo "--- version"; "$AGENT" --version
} > "$ART/binary-info.txt" 2>&1
if "$AGENT" --version >/dev/null 2>&1; then V=pass; else V=fail; fi
res 10 "linux-x64 binary runs on the runner (glibc $(ldd --version | head -1 | awk '{print $NF}'), required >= 2.35)" "$V" "$(tr '\n' ' ' < "$ART/binary-info.txt" | cut -c1-900)"
mkdir -p "$ART/help"
for c in "--help" "--help-agent" "connect --help" "daemon --help" "daemon start --help" "cert --help" "type-unicode --help"; do
  "$AGENT" $c > "$ART/help/$(echo "$c" | tr ' -' '__').txt" 2>&1
done
[ "$V" = pass ] || exit 1

# ---- RDP server: FreeRDP shadow + Xvfb ----
sudo apt-get update -qq >> "$LOG" 2>&1
apt-cache policy freerdp3-shadow-x11 freerdp2-shadow-x11 winpr3-utils winpr-utils xrdp 2>&1 | tee "$ART/apt-policy.txt" >> "$LOG"
SHADOW_PKG=""; WINPR_PKG=""
for p in freerdp3-shadow-x11 freerdp2-shadow-x11; do
  if apt-cache policy "$p" | grep -q 'Candidate: [^(]'; then SHADOW_PKG="$p"; break; fi
done
for p in winpr3-utils winpr-utils; do
  if apt-cache policy "$p" | grep -q 'Candidate: [^(]'; then WINPR_PKG="$p"; break; fi
done
say "shadow package: ${SHADOW_PKG:-none}; winpr utils: ${WINPR_PKG:-none}"
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq xvfb python3-tk python3-pil openssl procps iproute2 $SHADOW_PKG $WINPR_PKG >> "$LOG" 2>&1 || say "apt-get install returned $?"
SHADOW="$(command -v freerdp-shadow-cli freerdp-shadow-cli3 freerdp-shadow-cli2 2>/dev/null | head -1)"
HASH="$(command -v winpr-hash winpr3-hash winpr-hash3 2>/dev/null | head -1)"
{ echo "shadow=$SHADOW hash=$HASH"; [ -n "$SHADOW_PKG" ] && dpkg -L "$SHADOW_PKG" | grep -E 'bin/' ; [ -n "$WINPR_PKG" ] && dpkg -L "$WINPR_PKG" | grep -E 'bin/'; } >> "$LOG" 2>&1
if [ -z "$SHADOW" ]; then
  res 11 "FreeRDP shadow server available on ubuntu-latest" unavailable "no freerdp-shadow-cli binary after apt install (packages tried: freerdp3-shadow-x11, freerdp2-shadow-x11)"; exit 0
fi
"$SHADOW" /version > "$ART/shadow-version.txt" 2>&1 || true
"$SHADOW" /help > "$ART/shadow-help.txt" 2>&1 || true

export DISPLAY=:99
Xvfb :99 -screen 0 1280x800x24 -nolisten tcp > "$ART/xvfb.log" 2>&1 &
sleep 2
RECV="$WORK/recv"; mkdir -p "$RECV"
python3 "$HERE/linux-receiver.py" "$RECV" > "$ART/receiver.log" 2>&1 &
sleep 3

PW="Rs!$(openssl rand -hex 16)aA1"; echo "::add-mask::$PW"
SAM="$WORK/SAM"
if [ -n "$HASH" ]; then "$HASH" -u rdpuser -p "$PW" > "$SAM" 2>>"$LOG"; fi
say "SAM file: $(wc -c < "$SAM" 2>/dev/null) bytes, fields: $(awk -F: '{print NF}' "$SAM" 2>/dev/null | head -1)"
if [ "$MODE" = "nla" ]; then
  SHADOW_ARGS=(/port:3389 /sam-file:"$SAM" /sec:nla)
else
  SHADOW_ARGS=(/port:3389 -auth /sec:tls)
fi
"$SHADOW" "${SHADOW_ARGS[@]}" > "$ART/shadow-server.log" 2>&1 &
SHADOW_PID=$!
for i in $(seq 1 30); do ss -ltn | grep -q '127.0.0.1:3389\|0.0.0.0:3389\|\*:3389\|\[::\]:3389' && break; sleep 1; done
ss -ltnp 2>/dev/null | grep 3389 | tee -a "$LOG" || true
if ! ss -ltn | grep -q ':3389'; then
  res 11 "FreeRDP shadow server ($MODE) listening on :3389" fail "server did not listen; see shadow-server.log: $(head -c 600 "$ART/shadow-server.log" | tr '\n' ' ')"; kill $SHADOW_PID 2>/dev/null; exit 0
fi
res 11 "FreeRDP shadow server ($MODE) listening on :3389" pass "$SHADOW ${SHADOW_ARGS[*]} (package ${SHADOW_PKG}); NLA documented: https://github.com/FreeRDP/FreeRDP/blob/master/server/shadow/cli/freerdp-shadow-cli.1.in (/sam-file, /sec:nla)"
CERT="$(find "$HOME/.config/freerdp" -name '*.crt' 2>/dev/null | head -1)"
say "server cert: ${CERT:-not found}"

export AGENT ART RECV MODE RDP_PW="$PW" SERVER_CERT="${CERT:-}"
python3 "$HERE/linux-driver.py" 2>&1 | tee "$ART/driver.log"
say "driver exit: ${PIPESTATUS[0]}"
cp -r "$RECV" "$ART/receiver-logs" 2>/dev/null
[ -f "$HOME/.local/state/ironrdp-agent/daemon.log" ] && cp "$HOME/.local/state/ironrdp-agent/daemon.log" "$ART/daemon.log"
ls -la "$HOME/.local/state/ironrdp-agent" "$HOME/.config/ironrdp-agent" > "$ART/agent-state-files.txt" 2>&1
kill $SHADOW_PID 2>/dev/null; pkill -f linux-receiver.py; pkill Xvfb
exit 0
