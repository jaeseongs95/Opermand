# ironrdp-agent adoption spike, Windows runner (items 1-9 and 12 of the spike plan).
# Everything runs on a GitHub-hosted windows-latest runner; nothing here is a product feature.
[CmdletBinding()]
param(
    [ValidateSet(0, 1)][int] $Nla = 1,
    [Parameter(Mandatory)][string] $ArtifactsDir,
    [Parameter(Mandatory)][string] $WorkDir,
    [Parameter(Mandatory)][string] $ReleaseBase,
    [Parameter(Mandatory)][string] $Asset,
    [Parameter(Mandatory)][string] $PinnedSha256
)
$ErrorActionPreference = 'Continue'
. (Join-Path $PSScriptRoot 'common.ps1')

$null = New-Item -ItemType Directory -Force -Path $ArtifactsDir, $WorkDir
Initialize-Spike -AgentExe 'unset' -ArtifactsDir $ArtifactsDir
function Rec { param($Item, $Check, $Verdict, $Detail = '') Add-Result -Item $Item -Check "[nla=$Nla] $Check" -Verdict $Verdict -Detail $Detail }
function Invoke-Phase {
    param([string] $Name, [scriptblock] $Body)
    Write-Host "::group::$Name"
    Write-Log "=== PHASE $Name ==="
    try { & $Body } catch { Rec 'harness' "phase '$Name' threw" 'fail' ($_ | Out-String) }
    Write-Host '::endgroup::'
}

$script:Props = @('--prop', 'desktopwidth:i:1280', '--prop', 'desktopheight:i:800', '--prop', 'ironrdp_autologon:i:1',
    '--prop', 'compression:i:0', '--prop', 'ironrdp_colordepth:i:16', '--prop', 'ironrdp_serverpointer:i:0')
$script:Computer = $env:COMPUTERNAME

function Connect-Rdp {
    param([string] $Ep, [string] $Server, [string] $User, [string] $Pw, [string[]] $Extra = @(), [string[]] $Props = $script:Props)
    return Invoke-Agent -Endpoint $Ep -Env @{ RDP_USERNAME = "$script:Computer\$User"; RDP_PASSWORD = $Pw } `
        -AgentArgs (@('connect', '--no-prompt', '--auto-start', '--server', $Server) + $Props + $Extra)
}

function Get-TrustCommand {
    param([string] $StatusText)
    if ($StatusText -match 'cert trust\s+(\S+)\s+([0-9A-Fa-f:]{64,95})') { return [pscustomobject]@{ Endpoint = $Matches[1]; Fingerprint = $Matches[2] } }
    return $null
}

function Get-ServerCertSha256 {
    $c = Get-ChildItem 'Cert:\LocalMachine\Remote Desktop' -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $c) { return $null }
    return [Convert]::ToHexString([System.Security.Cryptography.SHA256]::HashData($c.RawData)).ToLower()
}

function Stop-AllAgents {
    foreach ($ep in @($script:Endpoints)) {
        Invoke-Agent -Endpoint $ep -AgentArgs @('disconnect') -TimeoutSec 20 | Out-Null
        Invoke-Agent -Endpoint $ep -AgentArgs @('daemon', 'stop') -TimeoutSec 20 | Out-Null
    }
    Get-Process -Name 'ironrdp-agent' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
}
$script:Endpoints = @('\\.\pipe\spike-main', '\\.\pipe\spike-b', '\\.\pipe\spike-ctl', '\\.\pipe\spike-idle', '\\.\pipe\spike-pp', '\\.\pipe\spike-kill')

# ============================================================================================
Invoke-Phase 'P0 download, verify, inspect (item 12)' {
    $archive = Join-Path $WorkDir $Asset
    & curl.exe -fsSL -o $archive "$ReleaseBase/$Asset"
    & curl.exe -fsSL -o "$archive.sha256" "$ReleaseBase/$Asset.sha256"
    $actual = (Get-FileHash -Algorithm SHA256 $archive).Hash.ToLower()
    $sidecar = ((Get-Content "$archive.sha256" -Raw).Trim() -split '\s+')[0].ToLower()
    Save-Text 'sha256-verification.txt' "pinned=$PinnedSha256`nsidecar=$sidecar`nactual=$actual`nsize=$((Get-Item $archive).Length)"
    if ($actual -ne $PinnedSha256.ToLower() -or $sidecar -ne $PinnedSha256.ToLower()) {
        Rec '12' 'archive SHA-256 equals pinned value and sidecar' 'fail' "pinned=$PinnedSha256 sidecar=$sidecar actual=$actual"
        throw 'SHA-256 mismatch; refusing to run the program'
    }
    Rec '12' 'archive SHA-256 equals pinned value and sidecar' 'pass' "sha256=$actual"
    $x = Join-Path $WorkDir 'agent'
    New-Item -ItemType Directory -Force -Path $x | Out-Null
    & tar.exe -xzf $archive -C $x
    Save-Text 'archive-listing.txt' ((& tar.exe -tzvf $archive) -join "`n")
    $exe = Get-ChildItem -Path $x -Recurse -Filter 'ironrdp-agent.exe' | Select-Object -First 1
    $script:AgentExe = $exe.FullName
    Initialize-Spike -AgentExe $script:AgentExe -ArtifactsDir $ArtifactsDir
    $files = (Get-ChildItem -Path $x -Recurse -File | ForEach-Object { "$($_.FullName.Substring($x.Length)) $($_.Length)" }) -join '; '
    Rec '12' 'binary size and archive contents' 'info' "archiveBytes=$((Get-Item $archive).Length) exeBytes=$($exe.Length) files=[$files]"
    $sig = Get-AuthenticodeSignature $script:AgentExe
    Rec '12' 'Authenticode signature on ironrdp-agent.exe' 'info' "status=$($sig.Status) signer=$($sig.SignerCertificate.Subject) message=$($sig.StatusMessage)"
    $ver = Invoke-Agent -NoEndpoint -AgentArgs @('--version')
    Rec '12' 'ironrdp-agent --version runs on a stock runner' $(if ($ver.ExitCode -eq 0) { 'pass' } else { 'fail' }) (Format-AgentResult $ver)
    # Runtime dependency: imports of the PE (VC runtime etc.)
    $dumpbin = $null
    $vsw = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
    if (Test-Path $vsw) {
        $vs = & $vsw -latest -products * -property installationPath
        if ($vs) { $dumpbin = Get-ChildItem -Path "$vs\VC\Tools\MSVC" -Recurse -Filter 'dumpbin.exe' -ErrorAction SilentlyContinue | Where-Object { $_.FullName -match 'Hostx64\\x64' } | Select-Object -First 1 }
    }
    if ($dumpbin) {
        $dep = (& $dumpbin.FullName /dependents $script:AgentExe) -join "`n"
        Save-Text 'pe-dependents.txt' $dep
        $vc = ($dep -split "`n" | Where-Object { $_ -match '(?i)vcruntime|msvcp|ucrtbase|api-ms-win-crt' }) -join ', '
        Rec '12' 'runtime dependencies (dumpbin /dependents)' 'info' "VC/UCRT-related imports: [$vc]. Full list in pe-dependents.txt"
    } else {
        Rec '12' 'runtime dependencies (dumpbin /dependents)' 'unavailable' 'dumpbin.exe not found on the runner image'
    }
}
if (-not $script:AgentExe -or $script:AgentExe -eq 'unset') { Write-Log 'no agent binary; aborting'; exit 1 }

Invoke-Phase 'P0b help output (item 7, 12)' {
    $dir = Join-Path $ArtifactsDir 'help'
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $cmds = @(@('--help'), @('--help-agent'), @('connect', '--help'), @('status', '--help'), @('wait-frame', '--help'), @('screenshot', '--help'),
        @('mouse-move', '--help'), @('mouse-button', '--help'), @('wheel', '--help'), @('key-scancode', '--help'), @('key-unicode', '--help'),
        @('type-unicode', '--help'), @('disconnect', '--help'), @('daemon', '--help'), @('daemon', 'start', '--help'), @('cert', '--help'),
        @('cert', 'trust', '--help'), @('session', '--help'), @('log', '--help'))
    foreach ($c in $cmds) {
        $r = Invoke-Agent -NoEndpoint -AgentArgs $c -TimeoutSec 20
        $name = ($c -join '_').Replace('-', '')
        [System.IO.File]::WriteAllText((Join-Path $dir "$name.txt"), ($r.Out + $r.Err), $script:Utf8)
    }
    $connectHelp = [System.IO.File]::ReadAllText((Join-Path $dir 'connect_help.txt'))
    $hits = [regex]::Matches($connectHelp, '(?im)^.*(stdin|password-file|password-stdin|--password|RDP_PASSWORD).*$') | ForEach-Object { $_.Value.Trim() }
    Rec '7' 'connect --help: credential input paths' 'info' ("lines mentioning password/stdin: " + ($hits -join ' || '))
    $stdinFlag = $connectHelp -match '(?i)password-stdin|password-file|--stdin'
    Rec '7' 'dedicated stdin/file password input exists' $(if ($stdinFlag) { 'pass' } else { 'fail' }) $(if ($stdinFlag) { 'a stdin/file option is present in connect --help' } else { 'no stdin/file password option in connect --help; only --password flag and RDP_PASSWORD env (and --rdp-file / daemon --overlay)' })
    Rec '12' '--help / --help-agent output captured' 'pass' 'see help/ in the artifact'
}

# ============================================================================================
Invoke-Phase 'P1 enable local RDP (item 1)' {
    Install-Receiver -SourceDir $PSScriptRoot
    $en = Enable-LocalRdp -Nla $Nla
    Save-Text 'rdp-enable.txt' ($en | Format-List | Out-String)
    $v = if ($en.Listening -and $en.UserAuthentication -eq $Nla) { 'pass' } else { 'fail' }
    Rec '1' 'RDP service on 127.0.0.1:3389 with requested NLA setting' $v "listening=$($en.Listening) UserAuthentication=$($en.UserAuthentication) SecurityLayer=$($en.SecurityLayerReg) $($en.Wmi)"
    $script:Pw1 = New-SpikeUser 'rdpspike1'
    $script:Pw2 = New-SpikeUser 'rdpspike2'
    $script:Pw3 = New-SpikeUser 'rdpspike3'
    $mem = (Get-LocalGroupMember -Group 'Remote Desktop Users' | ForEach-Object { $_.Name }) -join ', '
    Rec '1' 'test users created and in Remote Desktop Users' 'info' "members=$mem"
    $os = (Get-CimInstance Win32_OperatingSystem)
    Rec '1' 'runner OS' 'info' "$($os.Caption) build $($os.BuildNumber)"
}

# ============================================================================================
Invoke-Phase 'P2 strict TLS, certificate pinning, NLA connect (items 1, 2, 6)' {
    $ep = '\\.\pipe\spike-main'
    $start = Invoke-Agent -Endpoint $ep -AgentArgs @('daemon', 'start')
    Save-Text 'daemon-start-main.txt' (Format-AgentResult $start)
    Rec '9' 'daemon start --endpoint (background)' $(if ($start.ExitCode -eq 0) { 'pass' } else { 'fail' }) (Format-AgentResult $start)

    $c1 = Connect-Rdp -Ep $ep -Server '127.0.0.1:3389' -User 'rdpspike1' -Pw $script:Pw1
    Save-Text 'connect1-strict-nopin.txt' (Format-AgentResult $c1)
    $s1 = Wait-AgentConnected -Endpoint $ep -TimeoutSec 60 -Label 'c1-strict-nopin'
    $srv = Get-ServerCertSha256
    if ($s1.State -eq 'Connected') {
        Rec '6' 'strict TLS (default) with self-signed RDP cert, no pin' 'info' "connected without any pin; status: $($s1.Text.Trim())"
    } else {
        $tc = Get-TrustCommand $s1.Text
        $fpNorm = if ($tc) { $tc.Fingerprint.Replace(':', '').ToLower() } else { '' }
        $match = ($fpNorm -and $srv -and $fpNorm -eq $srv)
        Rec '6' 'strict TLS (default): self-signed cert is rejected and status reports the fingerprint' $(if ($tc) { 'pass' } else { 'fail' }) "state=$($s1.State); fingerprint reported=$fpNorm; serverCertSha256(from Windows store)=$srv; equal=$match; status text: $($s1.Text.Trim())"
        $fp = if ($tc) { $tc.Fingerprint } else { $srv }
        $ep2 = if ($tc) { $tc.Endpoint } else { '127.0.0.1:3389' }
        $trust = Invoke-Agent -NoEndpoint -AgentArgs @('cert', 'trust', $ep2, $fp)
        Rec '6' 'cert trust HOST:PORT SHA256' $(if ($trust.ExitCode -eq 0) { 'pass' } else { 'fail' }) (Format-AgentResult $trust)
        $list = Invoke-Agent -NoEndpoint -AgentArgs @('cert', 'list')
        Save-Text 'cert-list.txt' (Format-AgentResult $list)
        $c2 = Connect-Rdp -Ep $ep -Server '127.0.0.1:3389' -User 'rdpspike1' -Pw $script:Pw1
        $s1 = Wait-AgentConnected -Endpoint $ep -TimeoutSec 90 -Label 'c2-strict-pinned'
    }
    $ok = ($s1.State -eq 'Connected')
    Rec '2' "ironrdp-agent connects to 127.0.0.1:3389 (NLA=$Nla) with strict TLS + pinned cert" $(if ($ok) { 'pass' } else { 'fail' }) "state=$($s1.State) $($s1.Width)x$($s1.Height) frame=$($s1.Frame); status: $($s1.Text.Trim())"
    $script:MainConnected = $ok
    $log = Invoke-Agent -Endpoint $ep -AgentArgs @('log')
    Save-Text 'session-log-main.txt' ($log.Out + $log.Err)
    $credsspLines = ($log.Out -split "`n" | Where-Object { $_ -match '(?i)credssp|nla|hybrid|ntlm|kerberos|security protocol|selected protocol' } | Select-Object -First 12) -join ' || '
    Rec '2' 'session log lines about the security protocol' 'info' $credsspLines

    # Evidence from the server side: who logged on and how.
    try {
        $ev = Get-WinEvent -FilterHashtable @{ LogName = 'Microsoft-Windows-TerminalServices-RemoteConnectionManager/Operational'; Id = 1149 } -MaxEvents 5 -ErrorAction Stop | ForEach-Object { $_.Message }
        Save-Text 'event-1149.txt' ($ev -join "`n---`n")
    } catch { Save-Text 'event-1149.txt' "none: $($_.Exception.Message)" }
    try {
        $ev = Get-WinEvent -FilterHashtable @{ LogName = 'Security'; Id = 4624 } -MaxEvents 60 -ErrorAction Stop | Where-Object { $_.Message -match 'rdpspike' -and $_.Message -match 'Logon Type:\s+(10|3)' } |
            Select-Object -First 4 | ForEach-Object { $_.Message }
        Save-Text 'event-4624.txt' ($ev -join "`n---`n")
    } catch { Save-Text 'event-4624.txt' "none: $($_.Exception.Message)" }

    # Negative control: a client that does not offer CredSSP must be refused when the server requires NLA.
    $ep3 = '\\.\pipe\spike-ctl'
    Invoke-Agent -Endpoint $ep3 -AgentArgs @('daemon', 'start', '--skip-certificate-check') | Out-Null
    $nc = Connect-Rdp -Ep $ep3 -Server '127.0.0.1:3389' -User 'rdpspike3' -Pw $script:Pw3 -Extra @('--prop', 'enablecredsspsupport:i:0')
    $sn = Wait-AgentConnected -Endpoint $ep3 -TimeoutSec 40 -Label 'ctl-no-credssp'
    if ($Nla -eq 1) {
        Rec '2' 'control: client without CredSSP is refused by an NLA-required server (proves NLA is enforced)' $(if ($sn.State -ne 'Connected') { 'pass' } else { 'fail' }) "state=$($sn.State); status: $($sn.Text.Trim())"
    } else {
        Rec '2' 'control (NLA off): client without CredSSP connects' $(if ($sn.State -eq 'Connected') { 'pass' } else { 'fail' }) "state=$($sn.State); status: $($sn.Text.Trim())"
    }
    Invoke-Agent -Endpoint $ep3 -AgentArgs @('disconnect') | Out-Null
    Stop-SpikeSessions -OnlyUser 'rdpspike3'
    # --skip-certificate-check with default (CredSSP on)
    $sk = Connect-Rdp -Ep $ep3 -Server '127.0.0.1:3389' -User 'rdpspike3' -Pw $script:Pw3
    $ss = Wait-AgentConnected -Endpoint $ep3 -TimeoutSec 90 -Label 'ctl-skipcert'
    Rec '6' 'daemon --skip-certificate-check connects without a pin (CredSSP on)' $(if ($ss.State -eq 'Connected') { 'pass' } else { 'fail' }) "state=$($ss.State); status: $($ss.Text.Trim())"
    $script:CtlConnected = ($ss.State -eq 'Connected')
}

# ============================================================================================
Invoke-Phase 'P3 screenshot (item 3) and credential exposure (item 7)' {
    if (-not $script:MainConnected) { Rec '3' 'screenshot' 'unavailable' 'main session not connected'; return }
    $ep = '\\.\pipe\spike-main'
    $rx = Wait-ReceiverReady -User 'rdpspike1' -TimeoutSec 150
    $script:Rx1 = $rx
    if ($rx) { Rec '1' 'interactive desktop of the RDP session is usable (receiver app started in the session)' 'pass' "session=$($rx.session) screen=$($rx.screen.w)x$($rx.screen.h) user=$($rx.user)" }
    else { Rec '1' 'interactive desktop of the RDP session is usable (receiver app started in the session)' 'fail' 'receiver did not start within 150 s' }
    Start-Sleep -Seconds 2
    $png = Join-Path $ArtifactsDir 'screenshot-main-1.png'
    $r = Invoke-Agent -Endpoint $ep -AgentArgs @('screenshot', $png)
    $t = Test-Png $png
    Rec '3' 'screenshot writes a valid, non-uniform PNG' $(if ($r.ExitCode -eq 0 -and $t.Valid) { 'pass' } else { 'fail' }) "$(Format-AgentResult $r) | $($t.Text)"
    if ($t.Valid -and $rx) {
        $okRes = ($t.Width -eq 1280 -and $t.Height -eq 800)
        Rec '3' 'screenshot resolution equals the requested desktop size (1280x800)' $(if ($okRes) { 'pass' } else { 'fail' }) "png=$($t.Width)x$($t.Height) receiverScreen=$($rx.screen.w)x$($rx.screen.h)"
    }
    # Credential exposure
    $procs = Get-AgentProcessCommandLines
    Save-Text 'agent-processes.txt' ($procs | ForEach-Object { "pid=$($_.Pid) ppid=$($_.ParentPid) cmd=$($_.CommandLine)" }) 
    $leak = @($procs | Where-Object { $_.CommandLine -and (($_.CommandLine -like "*$script:Pw1*") -or ($_.CommandLine -like "*$script:Pw3*") -or ($_.CommandLine -match '(?i)--password|-p\s')) })
    Rec '7' 'password absent from the command line of every ironrdp-agent process while sessions are live (env-var path)' $(if ($leak.Count -eq 0 -and $procs.Count -gt 0) { 'pass' } else { 'fail' }) "processes=$($procs.Count) leakCount=$($leak.Count); cmdlines (secrets masked): $(($procs | ForEach-Object { Protect-Text $_.CommandLine }) -join ' || ')"
    $paths = @("$env:LOCALAPPDATA\ironrdp-agent", "$env:APPDATA\ironrdp-agent")
    $found = @()
    foreach ($p in $paths) { if (Test-Path $p) { Get-ChildItem $p -Recurse -File -ErrorAction SilentlyContinue | ForEach-Object { $txt = [System.IO.File]::ReadAllText($_.FullName); foreach ($pw in @($script:Pw1, $script:Pw2, $script:Pw3)) { if ($txt.Contains($pw)) { $found += $_.FullName } } } } }
    $listing = ($paths | ForEach-Object { if (Test-Path $_) { Get-ChildItem $_ -Recurse -File | ForEach-Object { "$($_.FullName) $($_.Length)" } } }) -join "`n"
    Save-Text 'agent-state-files.txt' $listing
    Rec '7' 'password not written to the agent log/state files on disk' $(if ($found.Count -eq 0) { 'pass' } else { 'fail' }) "files containing a password: [$($found -join ', ')]"
    $props = Invoke-Agent -Endpoint $ep -AgentArgs @('props') -TimeoutSec 20
    $pl = ($props.Out + $props.Err)
    Save-Text 'props-main.txt' $pl
    Rec '7' 'props/status output does not echo the password' $(if (($pl -notlike "*$script:Pw1*")) { 'pass' } else { 'fail' }) 'checked output of `props`'
}

# ============================================================================================
$script:Unit = 'Hello, World! 0123456789 ~!@#$%^&*()_+-=[]{}|;'':,./<>? \ "q" ' + [regex]::Unescape('\ud55c\uae00 \ud14c\uc2a4\ud2b8 \uac00\ub098\ub2e4') + ' caf' + [char]0x00E9 + ' ' + [char]0x20AC + [char]0x2603
function Get-ScalarCount { param([string] $s) return $s.Length - [regex]::Matches($s, '[\uD800-\uDBFF][\uDC00-\uDFFF]').Count }

function Ensure-InputFocus {
    param([string] $User, [string] $Ep)
    $st = Read-ReceiverJson -User $User -Name 'state.json'
    if ($st -and $st.focused -eq 'input') { return 'focused already' }
    $lay = Read-ReceiverJson -User $User -Name 'ready.json'
    Invoke-Agent -Endpoint $Ep -AgentArgs @('mouse-move', '--x', "$($lay.input.x)", '--y', "$($lay.input.y)") | Out-Null
    Invoke-Agent -Endpoint $Ep -AgentArgs @('mouse-button', '--button', 'left', '--pressed', 'true') | Out-Null
    Invoke-Agent -Endpoint $Ep -AgentArgs @('mouse-button', '--button', 'left', '--pressed', 'false') | Out-Null
    Start-Sleep -Milliseconds 600
    $st = Read-ReceiverJson -User $User -Name 'state.json'
    return "clicked input to focus; focused=$($st.focused)"
}

function Test-TypeCase {
    param([string] $User, [string] $Ep, [string] $Name, [string] $Text, [int] $Chunk = 96)
    [void](Reset-Receiver -User $User)
    $f = Ensure-InputFocus -User $User -Ep $Ep
    $chunks = Split-Scalars -Text $Text -Max $Chunk
    $swSend = [System.Diagnostics.Stopwatch]::StartNew()
    $bad = @()
    $i = 0
    foreach ($c in $chunks) {
        $r = Invoke-Agent -Endpoint $Ep -AgentArgs @('type-unicode', '--text', $c) -TimeoutSec 30
        if ($r.ExitCode -ne 0) { $bad += "chunk$i exit=$($r.ExitCode) $($r.Err.Trim()) $($r.Out.Trim())" }
        $i++
    }
    $swSend.Stop()
    $exp = $script:Utf8.GetBytes($Text)
    $w = Wait-ReceiverStable -User $User -Expected $exp
    $cmp = Compare-Text -Expected $Text -ActualBytes $w.Bytes
    return [pscustomobject]@{ Name = $Name; Equal = $cmp.Equal; Detail = "chunks=$($chunks.Count) sendMs=$($swSend.ElapsedMilliseconds) settleMs=$($w.SettleMs) cliFailures=$($bad.Count) [$($bad -join '; ')] focus='$f' $($cmp.Detail)" }
}

Invoke-Phase 'P4 input: type-unicode (item 4)' {
    if (-not $script:MainConnected -or -not $script:Rx1) { Rec '4' 'input tests' 'unavailable' 'no session or receiver'; return }
    $u = 'rdpspike1'; $ep = '\\.\pipe\spike-main'
    $uc = Get-ScalarCount $script:Unit
    Rec '4' 'test string facts' 'info' "unit scalars=$uc utf16=$($script:Unit.Length)"
    $r = Test-TypeCase -User $u -Ep $ep -Name 'U1 mixed <=96' -Text $script:Unit
    Rec '4' 'type-unicode: mixed ASCII/Hangul/symbols, <=96 chars, byte-exact at target' $(if ($r.Equal) { 'pass' } else { 'fail' }) $r.Detail
    $pad96 = $script:Unit
    while ((Get-ScalarCount $pad96) -lt 96) { $pad96 += 'x' }
    $r = Test-TypeCase -User $u -Ep $ep -Name 'U2 exactly 96' -Text $pad96
    Rec '4' "type-unicode: exactly 96 chars ($(Get-ScalarCount $pad96)) byte-exact" $(if ($r.Equal) { 'pass' } else { 'fail' }) $r.Detail
    # 97 characters must be refused by the CLI, nothing delivered
    [void](Reset-Receiver -User $u)
    $r97 = Invoke-Agent -Endpoint $ep -AgentArgs @('type-unicode', '--text', ($pad96 + 'y'))
    Start-Sleep -Seconds 2
    $got = (Get-ReceiverBytes -User $u).Length
    Rec '4' '97 chars in one call is rejected and nothing is delivered' $(if ($r97.ExitCode -ne 0 -and $got -eq 0) { 'pass' } else { 'fail' }) "$(Format-AgentResult $r97) receiverBytes=$got"

    # Long mixed text split into <=96-char calls, repeated to catch intermittent loss/reordering
    $mk = { param($n) $sb = [System.Text.StringBuilder]::new(); for ($i = 1; $i -le $n; $i++) { [void]$sb.Append(('[{0:0000}] ' -f $i)).Append($script:Unit).Append(' ') }; $sb.ToString() }
    $long1 = & $mk 10
    $okc = 0; $fails = @()
    for ($it = 1; $it -le 5; $it++) {
        $r = Test-TypeCase -User $u -Ep $ep -Name "L1.$it" -Text $long1
        if ($r.Equal) { $okc++ } else { $fails += "iter$it : $($r.Detail)" }
    }
    Rec '4' "type-unicode: ~$($long1.Length)-char mixed text as sequential <=96 calls, 5 repetitions, byte-exact" $(if ($okc -eq 5) { 'pass' } else { 'fail' }) "passed $okc/5. $($fails -join ' ### ')"
    $long2 = & $mk 40
    $okc = 0; $fails = @()
    for ($it = 1; $it -le 2; $it++) {
        $r = Test-TypeCase -User $u -Ep $ep -Name "L2.$it" -Text $long2
        if ($r.Equal) { $okc++ } else { $fails += "iter$it : $($r.Detail)" }
    }
    Rec '4' "type-unicode: ~$($long2.Length)-char mixed text as sequential <=96 calls, 2 repetitions, byte-exact" $(if ($okc -eq 2) { 'pass' } else { 'fail' }) "passed $okc/2. $($fails -join ' ### ')"
    # Chunk size 1 (worst case ordering across many tiny requests)
    $short = $script:Unit
    $r = Test-TypeCase -User $u -Ep $ep -Name 'C1 one scalar per call' -Text $short -Chunk 1
    Rec '4' 'type-unicode: one character per call (many tiny requests) preserves order' $(if ($r.Equal) { 'pass' } else { 'fail' }) $r.Detail
    # Informational edge cases
    $astral = 'A' + [char]::ConvertFromUtf32(0x1F600) + 'B'
    $r = Test-TypeCase -User $u -Ep $ep -Name 'E1 astral' -Text $astral
    Rec '4' 'type-unicode: character outside the BMP (U+1F600)' 'info' "equal=$($r.Equal) $($r.Detail)"
    $nl = "line1`nline2"
    $r = Test-TypeCase -User $u -Ep $ep -Name 'E2 newline' -Text $nl
    $b = Get-ReceiverBytes -User $u
    Rec '4' 'type-unicode: embedded LF character' 'info' "equal=$($r.Equal) receivedHex=$([Convert]::ToHexString($b)) $($r.Detail)"
    $r = Test-TypeCase -User $u -Ep $ep -Name 'E3 tab' -Text "a`tb"
    $b = Get-ReceiverBytes -User $u
    Rec '4' 'type-unicode: embedded TAB character' 'info' "equal=$($r.Equal) receivedHex=$([Convert]::ToHexString($b)) $($r.Detail)"
    # Screenshot after typing differs from the first one
    $png2 = Join-Path $ArtifactsDir 'screenshot-main-2.png'
    Invoke-Agent -Endpoint $ep -AgentArgs @('screenshot', $png2) | Out-Null
    $d = [RdpSpikeImage]::DiffPixels((Join-Path $ArtifactsDir 'screenshot-main-1.png'), $png2)
    Rec '3' 'screenshot reflects screen changes after input (pixel diff vs first screenshot)' $(if ($d -gt 100) { 'pass' } else { 'fail' }) "sampledDiffPixels=$d"
}

Invoke-Phase 'P5 input: key-scancode, mouse, wheel (item 4)' {
    if (-not $script:MainConnected -or -not $script:Rx1) { Rec '4' 'key/mouse tests' 'unavailable' 'no session or receiver'; return }
    $u = 'rdpspike1'; $ep = '\\.\pipe\spike-main'
    function Key { param($sc) Invoke-Agent -Endpoint $ep -AgentArgs @('key-scancode', '--scancode', $sc, '--pressed', 'true') | Out-Null; Invoke-Agent -Endpoint $ep -AgentArgs @('key-scancode', '--scancode', $sc, '--pressed', 'false') | Out-Null }
    function Cur { return $script:Utf8.GetString((Get-ReceiverBytes -User $u)) }
    [void](Reset-Receiver -User $u)
    [void](Ensure-InputFocus -User $u -Ep $ep)
    Key '0x1E'; Start-Sleep -Milliseconds 700; $s1 = Cur
    Invoke-Agent -Endpoint $ep -AgentArgs @('key-scancode', '--scancode', '0x2A', '--pressed', 'true') | Out-Null
    Key '0x1E'
    Invoke-Agent -Endpoint $ep -AgentArgs @('key-scancode', '--scancode', '0x2A', '--pressed', 'false') | Out-Null
    Start-Sleep -Milliseconds 700; $s2 = Cur
    Key '0x1C'; Key '0x30'; Start-Sleep -Milliseconds 700; $s3 = Cur
    Key '0x0E'; Start-Sleep -Milliseconds 700; $s4 = Cur
    Invoke-Agent -Endpoint $ep -AgentArgs @('key-scancode', '--scancode', '0x1D', '--pressed', 'true') | Out-Null
    Key '0x1E'
    Invoke-Agent -Endpoint $ep -AgentArgs @('key-scancode', '--scancode', '0x1D', '--pressed', 'false') | Out-Null
    Key '0x0E'; Start-Sleep -Milliseconds 700; $s5 = Cur
    $esc = { param($t) $t.Replace("`r", '\r').Replace("`n", '\n') }
    $ok = ($s1 -eq 'a') -and ($s2 -eq 'aA') -and ($s3 -eq "aA`r`nb") -and ($s4 -eq "aA`r`n") -and ($s5 -eq '')
    Rec '4' 'key-scancode: letter, Shift+letter, Enter, Backspace, Ctrl+A then Backspace observed at target' $(if ($ok) { 'pass' } else { 'fail' }) ("after a='$(& $esc $s1)' after Shift+a='$(& $esc $s2)' after Enter,b='$(& $esc $s3)' after Backspace='$(& $esc $s4)' after Ctrl+A,Backspace='$(& $esc $s5)'")

    $lay = Read-ReceiverJson -User $u -Name 'ready.json'
    $mm = Invoke-Agent -Endpoint $ep -AgentArgs @('mouse-move', '--x', '321', '--y', '123')
    Start-Sleep -Milliseconds 500
    $st = Read-ReceiverJson -User $u -Name 'state.json'
    $okm = ($st.cursor.x -eq 321 -and $st.cursor.y -eq 123)
    Rec '4' 'mouse-move: cursor position at target equals request (321,123)' $(if ($okm) { 'pass' } else { 'fail' }) "target cursor=($($st.cursor.x),$($st.cursor.y)) $(Format-AgentResult $mm)"
    $corner = Invoke-Agent -Endpoint $ep -AgentArgs @('mouse-move', '--x', '1279', '--y', '799')
    Start-Sleep -Milliseconds 500
    $st = Read-ReceiverJson -User $u -Name 'state.json'
    Rec '4' 'mouse-move: far corner (1279,799)' $(if ($st.cursor.x -eq 1279 -and $st.cursor.y -eq 799) { 'pass' } else { 'fail' }) "target cursor=($($st.cursor.x),$($st.cursor.y))"

    $evPath = Join-Path (Get-ReceiverDir $u) 'events.log'
    $before = if (Test-Path $evPath) { (Get-Content $evPath).Count } else { 0 }
    Invoke-Agent -Endpoint $ep -AgentArgs @('mouse-move', '--x', "$($lay.button.x)", '--y', "$($lay.button.y)") | Out-Null
    $d = Invoke-Agent -Endpoint $ep -AgentArgs @('mouse-button', '--button', 'left', '--pressed', 'true')
    $up = Invoke-Agent -Endpoint $ep -AgentArgs @('mouse-button', '--button', 'left', '--pressed', 'false')
    Invoke-Agent -Endpoint $ep -AgentArgs @('mouse-move', '--x', "$($lay.panel.x)", '--y', "$($lay.panel.y)") | Out-Null
    Invoke-Agent -Endpoint $ep -AgentArgs @('mouse-button', '--button', 'right', '--pressed', 'true') | Out-Null
    Invoke-Agent -Endpoint $ep -AgentArgs @('mouse-button', '--button', 'right', '--pressed', 'false') | Out-Null
    Invoke-Agent -Endpoint $ep -AgentArgs @('mouse-button', '--button', 'middle', '--pressed', 'true') | Out-Null
    Invoke-Agent -Endpoint $ep -AgentArgs @('mouse-button', '--button', 'middle', '--pressed', 'false') | Out-Null
    Start-Sleep -Seconds 1
    $ev = if (Test-Path $evPath) { @(Get-Content $evPath | Select-Object -Skip $before) } else { @() }
    $click = [bool]($ev | Where-Object { $_ -match '\|Click\|btnA' })
    $rdown = [bool]($ev | Where-Object { $_ -match '\|MouseDown\|panel\|Right\|' })
    $mdown = [bool]($ev | Where-Object { $_ -match '\|MouseDown\|panel\|Middle\|' })
    Rec '4' 'mouse-button left: click on the target button registered' $(if ($click) { 'pass' } else { 'fail' }) "events: $(($ev | Where-Object { $_ -match 'Click|MouseDown|MouseUp' }) -join ' ; ')"
    Rec '4' 'mouse-button right/middle: registered at target' $(if ($rdown -and $mdown) { 'pass' } else { 'fail' }) "right=$rdown middle=$mdown"

    Invoke-Agent -Endpoint $ep -AgentArgs @('mouse-move', '--x', "$($lay.scroll.x)", '--y', "$($lay.scroll.y)") | Out-Null
    $line0 = (Read-ReceiverJson -User $u -Name 'state.json').firstVisibleLine
    $before = if (Test-Path $evPath) { (Get-Content $evPath).Count } else { 0 }
    $w = Invoke-Agent -Endpoint $ep -AgentArgs @('wheel', '--delta', '-120')
    $w2 = Invoke-Agent -Endpoint $ep -AgentArgs @('wheel', '--delta', '-120')
    $w3 = Invoke-Agent -Endpoint $ep -AgentArgs @('wheel', '--delta', '-120')
    Start-Sleep -Seconds 1
    $line1 = (Read-ReceiverJson -User $u -Name 'state.json').firstVisibleLine
    $ev = if (Test-Path $evPath) { @(Get-Content $evPath | Select-Object -Skip $before) } else { @() }
    Rec '4' 'wheel -120 x3 scrolls the target text box down' $(if ($line1 -gt $line0) { 'pass' } else { 'fail' }) "firstVisibleLine $line0 -> $line1; wheel events: $(($ev | Where-Object { $_ -match 'Wheel' }) -join ' ; ') $(Format-AgentResult $w)"
    $up2 = Invoke-Agent -Endpoint $ep -AgentArgs @('wheel', '--delta', '120')
    Start-Sleep -Milliseconds 600
    $line2 = (Read-ReceiverJson -User $u -Name 'state.json').firstVisibleLine
    Rec '4' 'wheel +120 scrolls back up' $(if ($line2 -lt $line1) { 'pass' } else { 'fail' }) "firstVisibleLine $line1 -> $line2"
    $hw = Invoke-Agent -Endpoint $ep -AgentArgs @('wheel', '--delta', '120', '--horizontal')
    Rec '4' 'wheel --horizontal accepted by the CLI' $(if ($hw.ExitCode -eq 0) { 'info' } else { 'fail' }) (Format-AgentResult $hw)
}

# ============================================================================================
Invoke-Phase 'P6 acknowledgement semantics, queue pressure (item 5)' {
    if (-not $script:MainConnected -or -not $script:Rx1) { Rec '5' 'ack tests' 'unavailable' 'no session or receiver'; return }
    $u = 'rdpspike1'; $ep = '\\.\pipe\spike-main'
    [void](Reset-Receiver -User $u); [void](Ensure-InputFocus -User $u -Ep $ep)
    # What does a successful input command print / return, and how long after it does the target see it?
    $evPath = Join-Path (Get-ReceiverDir $u) 'text.bin'
    $t0 = [DateTime]::UtcNow
    $r = Invoke-Agent -Endpoint $ep -AgentArgs @('type-unicode', '--text', 'ACKTEST')
    $tRet = $r.EndedUtc
    $tEff = $null
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.ElapsedMilliseconds -lt 5000) { if ((Get-ReceiverBytes -User $u).Length -ge 7) { $tEff = [DateTime]::UtcNow; break }; Start-Sleep -Milliseconds 5 }
    $lag = if ($tEff) { [int](($tEff - $tRet).TotalMilliseconds) } else { -1 }
    Rec '5' 'type-unicode success output and exit code' 'info' (Format-AgentResult $r)
    Rec '5' 'time between CLI return and the target seeing the text (negative = target saw it first)' 'info' "cliMs=$($r.Ms) cliReturnToTargetSeenMs=$lag (target seen at +$([int](($tEff - $t0).TotalMilliseconds)) ms from start; null/-1 means not seen within 5 s)"
    foreach ($cmd in @(@('mouse-move', '--x', '10', '--y', '10'), @('mouse-button', '--button', 'left', '--pressed', 'false'), @('wheel', '--delta', '120'), @('key-scancode', '--scancode', '0x2A', '--pressed', 'false'), @('wait-frame', '--after-frame', '0', '--timeout-ms', '3000'), @('status'))) {
        $x = Invoke-Agent -Endpoint $ep -AgentArgs $cmd
        Rec '5' "output of '$($cmd -join ' ')'" 'info' (Format-AgentResult $x)
    }
    # Flood: many parallel type-unicode calls, each tagged; compare accepted vs delivered.
    [void](Reset-Receiver -User $u); [void](Ensure-InputFocus -User $u -Ep $ep)
    $exe = $script:AgentExe
    $n = 150
    $res = 1..$n | ForEach-Object -ThrottleLimit 50 -Parallel {
        $i = $_
        $letter = [char](97 + ($i % 26))
        $body = ([string]$letter) * 80
        $text = ('[{0:000}:{1}]' -f $i, $body)
        $o = & $using:exe --endpoint $using:ep type-unicode --text $text 2>&1 | Out-String
        [pscustomobject]@{ Id = $i; Text = $text; Exit = $LASTEXITCODE; Out = $o.Trim() }
    }
    $acc = @($res | Where-Object { $_.Exit -eq 0 }); $rej = @($res | Where-Object { $_.Exit -ne 0 })
    $exp = [System.Text.StringBuilder]::new(); foreach ($a in $acc) { [void]$exp.Append($a.Text) }
    $w = Wait-ReceiverStable -User $u -QuietMs 4000 -MaxSec 120
    $got = $script:Utf8.GetString($w.Bytes)
    $tokens = [regex]::Matches($got, '\[(\d{3}):([a-z])\2{79}\]') | ForEach-Object { [int]$_.Groups[1].Value }
    $accIds = $acc | ForEach-Object { $_.Id }
    $missing = @($accIds | Where-Object { $tokens -notcontains $_ })
    $extra = @($tokens | Where-Object { $accIds -notcontains $_ })
    $residue = [regex]::Replace($got, '\[(\d{3}):([a-z])\2{79}\]', '')
    $rejMsgs = ($rej | Group-Object { $_.Out } | ForEach-Object { "x$($_.Count): $($_.Name)" }) -join ' || '
    Rec '5' "parallel flood: $n concurrent type-unicode calls; accepted=$($acc.Count) rejected=$($rej.Count)" 'info' "rejection messages: $rejMsgs"
    $verdict = if ($missing.Count -eq 0 -and $extra.Count -eq 0 -and $residue.Length -eq 0) { 'pass' } else { 'fail' }
    Rec '5' 'every call the CLI reported as success was delivered intact; no rejected call leaked partial text' $verdict "accepted=$($acc.Count) delivered tokens=$($tokens.Count) missingAccepted=[$($missing -join ',')] extraRejected=[$($extra -join ',')] residueChars=$($residue.Length) receivedLen=$($got.Length)"
    Save-Text 'flood-received.txt' $got
    # mouse-move flood
    $res2 = 1..300 | ForEach-Object -ThrottleLimit 100 -Parallel {
        $o = & $using:exe --endpoint $using:ep mouse-move --x ([int]($_ % 1000)) --y 50 2>&1 | Out-String
        [pscustomobject]@{ Exit = $LASTEXITCODE; Out = $o.Trim() }
    }
    $bad2 = @($res2 | Where-Object { $_.Exit -ne 0 })
    Rec '5' 'parallel flood: 300 concurrent mouse-move calls' 'info' "failures=$($bad2.Count) messages: $(($bad2 | Group-Object Out | ForEach-Object { "x$($_.Count): $($_.Name)" }) -join ' || ')"
}

# ============================================================================================
Invoke-Phase 'P7 concurrency, lifetime (item 9)' {
    if (-not $script:MainConnected) { Rec '9' 'lifetime' 'unavailable' 'no main session'; return }
    $epA = '\\.\pipe\spike-main'; $epB = '\\.\pipe\spike-ctl'
    if (-not $script:CtlConnected) {
        Rec '9' 'two simultaneous sessions via --endpoint' 'fail' 'second session (spike-ctl) was not connected; see item 6 skip-cert result'
    } else {
        $rxB = Wait-ReceiverReady -User 'rdpspike3' -TimeoutSec 150
        $rxA = $script:Rx1
        if ($rxB) {
            [void](Reset-Receiver -User 'rdpspike1'); [void](Reset-Receiver -User 'rdpspike3')
            [void](Ensure-InputFocus -User 'rdpspike1' -Ep $epA); [void](Ensure-InputFocus -User 'rdpspike3' -Ep $epB)
            $ta = 'session-A ' + $script:Unit; $tb = 'session-B ' + [regex]::Unescape('\ud55c\uae00') + ' second'
            foreach ($c in (Split-Scalars $ta 96)) { Invoke-Agent -Endpoint $epA -AgentArgs @('type-unicode', '--text', $c) | Out-Null }
            foreach ($c in (Split-Scalars $tb 96)) { Invoke-Agent -Endpoint $epB -AgentArgs @('type-unicode', '--text', $c) | Out-Null }
            $wa = Wait-ReceiverStable -User 'rdpspike1' -Expected $script:Utf8.GetBytes($ta)
            $wb = Wait-ReceiverStable -User 'rdpspike3' -Expected $script:Utf8.GetBytes($tb)
            $ca = Compare-Text $ta $wa.Bytes; $cb = Compare-Text $tb $wb.Bytes
            Rec '9' 'two daemons (--endpoint) drive two RDP sessions at once; each target receives only its own text' $(if ($ca.Equal -and $cb.Equal) { 'pass' } else { 'fail' }) "A: $($ca.Detail) | B: $($cb.Detail)"
            $pa = Join-Path $ArtifactsDir 'screenshot-A.png'; $pb = Join-Path $ArtifactsDir 'screenshot-B.png'
            Invoke-Agent -Endpoint $epA -AgentArgs @('screenshot', $pa) | Out-Null; Invoke-Agent -Endpoint $epB -AgentArgs @('screenshot', $pb) | Out-Null
            Rec '9' 'independent screenshots from the two sessions' $(if ((Test-Png $pa).Valid -and (Test-Png $pb).Valid) { 'pass' } else { 'fail' }) "A: $((Test-Png $pa).Text) | B: $((Test-Png $pb).Text)"
        } else { Rec '9' 'two sessions: second receiver' 'fail' 'receiver of session B did not start' }
    }
    $procs = Get-AgentProcessCommandLines
    Rec '9' 'agent processes while two sessions are live' 'info' (($procs | ForEach-Object { "pid=$($_.Pid) ppid=$($_.ParentPid) cmd=$(Protect-Text $_.CommandLine)" }) -join ' || ')
    $pipes = (Get-PipeNames | Where-Object { $_ -match 'spike|ironrdp' }) -join ', '
    Rec '9' 'named pipes while live' 'info' $pipes

    # disconnect leaves the daemon; daemon stop removes it
    $d = Invoke-Agent -Endpoint $epB -AgentArgs @('disconnect')
    $ds = Invoke-Agent -Endpoint $epB -AgentArgs @('daemon', 'status')
    Rec '9' 'disconnect output and daemon status afterwards (daemon expected to stay up)' 'info' "disconnect: $(Format-AgentResult $d) | daemon status: $(Format-AgentResult $ds)"
    $sess = (Get-RdpSessions | ForEach-Object { "$($_.User)#$($_.Id)=$($_.State)" }) -join ', '
    Rec '9' 'server-side session state after client disconnect (session is left running on the host)' 'info' $sess
    $st = Invoke-Agent -Endpoint $epB -AgentArgs @('daemon', 'stop')
    Start-Sleep -Seconds 2
    $left = @(Get-AgentProcessCommandLines | Where-Object { $_.CommandLine -match 'spike-ctl' })
    $pipesB = @(Get-PipeNames | Where-Object { $_ -match 'spike-ctl' })
    Rec '9' 'daemon stop leaves no process and no pipe for that endpoint' $(if ($left.Count -eq 0 -and $pipesB.Count -eq 0) { 'pass' } else { 'fail' }) "daemon stop: $(Format-AgentResult $st) | leftoverProcesses=$($left.Count) leftoverPipes=$($pipesB -join ',')"
    # session A still alive after B is gone
    $sa = Get-AgentStatus -Endpoint $epA
    Rec '9' 'stopping daemon B does not disturb session A' $(if ($sa.State -eq 'Connected') { 'pass' } else { 'fail' }) "A state=$($sa.State)"

    $dA = Invoke-Agent -Endpoint $epA -AgentArgs @('disconnect')
    $sA = Invoke-Agent -Endpoint $epA -AgentArgs @('daemon', 'stop')
    Start-Sleep -Seconds 2
    $all = @(Get-Process -Name 'ironrdp-agent' -ErrorAction SilentlyContinue)
    $allPipes = @(Get-PipeNames | Where-Object { $_ -match 'spike|ironrdp' })
    Rec '9' 'after disconnect + daemon stop on every endpoint: no ironrdp-agent process, no IPC pipe left' $(if ($all.Count -eq 0 -and $allPipes.Count -eq 0) { 'pass' } else { 'fail' }) "processes=$($all.Count) pipes=[$($allPipes -join ',')] A disconnect: $(Format-AgentResult $dA) | A stop: $(Format-AgentResult $sA)"
    $files = ((@("$env:LOCALAPPDATA\ironrdp-agent", "$env:APPDATA\ironrdp-agent") | ForEach-Object { if (Test-Path $_) { Get-ChildItem $_ -Recurse -File | ForEach-Object { "$($_.FullName) $($_.Length)" } } }) -join '; ')
    Rec '9' 'files the agent leaves on disk after a clean stop' 'info' $files
    Stop-SpikeSessions
}

# ============================================================================================
Invoke-Phase 'P8 abnormal daemon exit and disconnected-server behaviour (items 5, 9)' {
    # Idle daemon with no session
    $idle = '\\.\pipe\spike-idle'
    Invoke-Agent -Endpoint $idle -AgentArgs @('daemon', 'start') | Out-Null
    $out = @()
    foreach ($cmd in @(@('status'), @('mouse-move', '--x', '5', '--y', '5'), @('type-unicode', '--text', 'x'), @('screenshot', (Join-Path $ArtifactsDir 'idle.png')), @('wait-frame', '--after-frame', '0', '--timeout-ms', '2000'), @('disconnect'))) {
        $x = Invoke-Agent -Endpoint $idle -AgentArgs $cmd
        $out += "[$($cmd -join ' ')] $(Format-AgentResult $x)"
    }
    Rec '5' 'daemon with no session: status and every input command give an explicit error' 'info' ($out -join "`n")
    Invoke-Agent -Endpoint $idle -AgentArgs @('daemon', 'stop') | Out-Null

    # Server side ends the session while the client is connected
    $ep = '\\.\pipe\spike-kill'
    Invoke-Agent -Endpoint $ep -AgentArgs @('daemon', 'start') | Out-Null
    $c = Connect-Rdp -Ep $ep -Server '127.0.0.1:3389' -User 'rdpspike2' -Pw $script:Pw2
    $s = Wait-AgentConnected -Endpoint $ep -TimeoutSec 90 -Label 'kill-connect'
    if ($s.State -ne 'Connected') { Rec '5' 'reconnect for disconnect tests' 'fail' "state=$($s.State) $($s.Text.Trim())"; return }
    $sid = (Get-RdpSessions | Where-Object { $_.User -eq 'rdpspike2' } | Select-Object -First 1).Id
    $t0 = Get-Date
    & logoff.exe $sid 2>&1 | Out-Null
    $trace = @()
    $lastKey = ''
    while (((Get-Date) - $t0).TotalSeconds -lt 30) {
        $x = Get-AgentStatus -Endpoint $ep
        $key = "$($x.Exit)|$($x.State)"
        if ($key -ne $lastKey) { $trace += ("+{0:0.0}s exit={1} state={2} | {3}" -f ((Get-Date) - $t0).TotalSeconds, $x.Exit, $x.State, ($x.Text.Trim() -replace '\s+', ' ')); $lastKey = $key }
        if ($x.State -ne 'Connected') { Start-Sleep -Seconds 2; $x2 = Get-AgentStatus -Endpoint $ep; $trace += "later: state=$($x2.State) | $(($x2.Text.Trim() -replace '\s+', ' '))"; break }
        Start-Sleep -Milliseconds 500
    }
    Rec '5' 'server logs the session off: how status reports it (time to notice and text)' 'info' ($trace -join "`n")
    $out = @()
    foreach ($cmd in @(@('mouse-move', '--x', '5', '--y', '5'), @('mouse-button', '--button', 'left', '--pressed', 'false'), @('key-scancode', '--scancode', '0x1E', '--pressed', 'true'), @('type-unicode', '--text', 'x'), @('wheel', '--delta', '120'), @('screenshot', (Join-Path $ArtifactsDir 'after-logoff.png')), @('wait-frame', '--after-frame', '0', '--timeout-ms', '2000'), @('disconnect'), @('daemon', 'status'))) {
        $x = Invoke-Agent -Endpoint $ep -AgentArgs $cmd
        $out += "[$($cmd -join ' ')] $(Format-AgentResult $x)"
    }
    Rec '5' 'after the session is gone: each command (exit code and text)' 'info' ($out -join "`n")
    Save-Text 'after-logoff-commands.txt' ($out -join "`n")

    # Abnormal daemon termination
    $c = Connect-Rdp -Ep $ep -Server '127.0.0.1:3389' -User 'rdpspike2' -Pw $script:Pw2
    $s = Wait-AgentConnected -Endpoint $ep -TimeoutSec 90 -Label 'kill-connect2'
    $procBefore = @(Get-AgentProcessCommandLines)
    $pipesBefore = @(Get-PipeNames | Where-Object { $_ -match 'spike|ironrdp' })
    $daemon = $procBefore | Where-Object { $_.CommandLine -match 'daemon' } | Select-Object -First 1
    if ($daemon) { Stop-Process -Id $daemon.Pid -Force }
    Start-Sleep -Seconds 3
    $procAfter = @(Get-Process -Name 'ironrdp-agent' -ErrorAction SilentlyContinue)
    $pipesAfter = @(Get-PipeNames | Where-Object { $_ -match 'spike|ironrdp' })
    $stat = Invoke-Agent -Endpoint $ep -AgentArgs @('status')
    $mm = Invoke-Agent -Endpoint $ep -AgentArgs @('mouse-move', '--x', '1', '--y', '1')
    $sess = (Get-RdpSessions | ForEach-Object { "$($_.User)#$($_.Id)=$($_.State)" }) -join ', '
    $leftFiles = ((@("$env:LOCALAPPDATA\ironrdp-agent", "$env:APPDATA\ironrdp-agent") | ForEach-Object { if (Test-Path $_) { Get-ChildItem $_ -Recurse -File | ForEach-Object { "$($_.Name) $($_.Length)" } } }) -join '; ')
    Rec '9' 'daemon killed (Stop-Process -Force) while connected: what is left' 'info' "daemon pid=$($daemon.Pid) processesAfter=$($procAfter.Count) pipesBefore=[$($pipesBefore -join ',')] pipesAfter=[$($pipesAfter -join ',')] | status: $(Format-AgentResult $stat) | mouse-move: $(Format-AgentResult $mm) | server sessions: $sess | files: $leftFiles"
    # Can the same endpoint be reused?
    $re = Invoke-Agent -Endpoint $ep -AgentArgs @('daemon', 'start')
    $c = Connect-Rdp -Ep $ep -Server '127.0.0.1:3389' -User 'rdpspike2' -Pw $script:Pw2
    $s = Wait-AgentConnected -Endpoint $ep -TimeoutSec 90 -Label 'kill-reconnect'
    Rec '9' 'after the daemon was killed, a new daemon on the same endpoint connects again' $(if ($s.State -eq 'Connected') { 'pass' } else { 'fail' }) "daemon start: $(Format-AgentResult $re) | state=$($s.State) $($s.Text.Trim())"
    Invoke-Agent -Endpoint $ep -AgentArgs @('disconnect') | Out-Null
    Invoke-Agent -Endpoint $ep -AgentArgs @('daemon', 'stop') | Out-Null
    Stop-SpikeSessions
}

# ============================================================================================
Invoke-Phase 'P9 arbitrary local port: portproxy and TCP relay (item 8)' {
    foreach ($variant in @('portproxy', 'relay')) {
        $port = if ($variant -eq 'portproxy') { 43389 } else { 43390 }
        $relay = $null
        if ($variant -eq 'portproxy') {
            Set-Service -Name iphlpsvc -StartupType Automatic -ErrorAction SilentlyContinue
            Start-Service -Name iphlpsvc -ErrorAction SilentlyContinue
            $np = (& netsh.exe interface portproxy add v4tov4 listenaddress=127.0.0.1 listenport=$port connectaddress=127.0.0.1 connectport=3389 2>&1) -join ' '
            $show = (& netsh.exe interface portproxy show all 2>&1) -join "`n"
            Write-Log "portproxy add: $np`n$show"
            $listening = Wait-TcpPort -HostName '127.0.0.1' -Port $port -TimeoutSec 10
            if (-not $listening) { Rec '8' 'netsh portproxy 127.0.0.1:43389 -> 127.0.0.1:3389 is reachable' 'fail' "add: $np; show: $show"; continue }
        } else {
            $relay = Start-TcpRelay -ListenPort $port -TargetHost '127.0.0.1' -TargetPort 3389
            Start-Sleep -Seconds 1
        }
        $ep = '\\.\pipe\spike-pp'
        Invoke-Agent -Endpoint $ep -AgentArgs @('daemon', 'start') | Out-Null
        $srv = "127.0.0.1:$port"
        $c = Connect-Rdp -Ep $ep -Server $srv -User 'rdpspike2' -Pw $script:Pw2
        $s = Wait-AgentConnected -Endpoint $ep -TimeoutSec 60 -Label "pp-$variant-nopin"
        $tc = Get-TrustCommand $s.Text
        if ($s.State -ne 'Connected') {
            Rec '8' "$variant : strict TLS, no pin for the new HOST:PORT" 'info' "pin is per host:port. state=$($s.State); trust command offered: $(if ($tc) { "$($tc.Endpoint) $($tc.Fingerprint)" } else { '(none)' }); status: $($s.Text.Trim())"
            if ($tc) {
                Invoke-Agent -NoEndpoint -AgentArgs @('cert', 'trust', $tc.Endpoint, $tc.Fingerprint) | Out-Null
                $c = Connect-Rdp -Ep $ep -Server $srv -User 'rdpspike2' -Pw $script:Pw2
                $s = Wait-AgentConnected -Endpoint $ep -TimeoutSec 90 -Label "pp-$variant-pinned"
            }
        }
        $ok = ($s.State -eq 'Connected')
        Rec '8' "$variant : connect through 127.0.0.1:$port -> 3389 (NLA=$Nla), strict TLS + pin" $(if ($ok) { 'pass' } else { 'fail' }) "state=$($s.State) $($s.Width)x$($s.Height); status: $($s.Text.Trim())"
        if ($ok) {
            $rx = Wait-ReceiverReady -User 'rdpspike2' -TimeoutSec 150
            $png = Join-Path $ArtifactsDir "screenshot-$variant.png"
            Invoke-Agent -Endpoint $ep -AgentArgs @('screenshot', $png) | Out-Null
            Rec '8' "$variant : screenshot through the forwarded port" $(if ((Test-Png $png).Valid) { 'pass' } else { 'fail' }) (Test-Png $png).Text
            if ($rx) {
                $t = Test-TypeCase -User 'rdpspike2' -Ep $ep -Name "pp-$variant" -Text $script:Unit
                Rec '8' "$variant : type-unicode through the forwarded port is byte-exact" $(if ($t.Equal) { 'pass' } else { 'fail' }) $t.Detail
            }
        }
        Invoke-Agent -Endpoint $ep -AgentArgs @('disconnect') | Out-Null
        Invoke-Agent -Endpoint $ep -AgentArgs @('daemon', 'stop') | Out-Null
        Stop-SpikeSessions -OnlyUser 'rdpspike2'
        if ($variant -eq 'portproxy') { & netsh.exe interface portproxy delete v4tov4 listenaddress=127.0.0.1 listenport=$port | Out-Null }
        if ($relay) { $relay.Stop() }
    }
}

Invoke-Phase 'P10 NLA without autologon property (design information)' {
    $ep = '\\.\pipe\spike-idle'
    Invoke-Agent -Endpoint $ep -AgentArgs @('daemon', 'start', '--skip-certificate-check') | Out-Null
    $props = @('--prop', 'desktopwidth:i:1280', '--prop', 'desktopheight:i:800')
    $c = Connect-Rdp -Ep $ep -Server '127.0.0.1:3389' -User 'rdpspike3' -Pw $script:Pw3 -Props $props
    $s = Wait-AgentConnected -Endpoint $ep -TimeoutSec 90 -Label 'noautologon'
    if ($s.State -eq 'Connected') {
        $rx = Wait-ReceiverReady -User 'rdpspike3' -TimeoutSec 60
        Start-Sleep -Seconds 3
        $png = Join-Path $ArtifactsDir 'screenshot-no-autologon.png'
        Invoke-Agent -Endpoint $ep -AgentArgs @('screenshot', $png) | Out-Null
        Rec '2' 'connect without ironrdp_autologon (only NLA credentials): session reaches the user desktop' 'info' "state=Connected receiverStarted=$([bool]$rx) (receiver reading ready.json proves a logged-on desktop). png: $((Test-Png $png).Text)"
    } else {
        Rec '2' 'connect without ironrdp_autologon (only NLA credentials)' 'info' "state=$($s.State) $($s.Text.Trim())"
    }
    Invoke-Agent -Endpoint $ep -AgentArgs @('disconnect') | Out-Null
    Invoke-Agent -Endpoint $ep -AgentArgs @('daemon', 'stop') | Out-Null
    Stop-SpikeSessions
}

Stop-AllAgents
Stop-SpikeSessions
Copy-Item -Path 'C:\rdp-spike\recv' -Destination (Join-Path $ArtifactsDir 'receiver-logs') -Recurse -Force -ErrorAction SilentlyContinue
foreach ($f in @("$env:LOCALAPPDATA\ironrdp-agent\daemon.log")) { if (Test-Path $f) { Save-Text 'daemon.log' ([System.IO.File]::ReadAllText($f)) } }
$summary = $script:Results | Group-Object verdict | ForEach-Object { "$($_.Name)=$($_.Count)" }
Write-Log "DONE nla=$Nla results: $($summary -join ' ')"
$script:Results | ConvertTo-Json -Depth 4 | Set-Content -Path (Join-Path $ArtifactsDir 'results.json') -Encoding utf8
exit 0
