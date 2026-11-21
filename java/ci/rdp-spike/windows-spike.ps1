# ironrdp-agent adoption spike, Windows runner (items 1-9 and 12 of the spike plan).
# Everything runs on a GitHub-hosted windows-latest runner; nothing here is a product feature.
# The 0.1.0 prebuilt CLI is much smaller than the upstream master README (no type-unicode, wait-frame, cert,
# daemon stop, --no-prompt, RDP_PASSWORD, ...). This script probes each capability and tests what exists.
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
$script:Caps = @{}

# Password comes from the daemon overlay (Start-AgentDaemon -Password); the CLI gets only the user name.
function Connect-Rdp {
    param([string] $Ep, [string] $Server, [string] $User, [string[]] $Extra = @(), [string[]] $Props = $script:Props)
    $a = @('connect', '--server', $Server, '-u', "$script:Computer\$User") + $Props + $Extra
    return Invoke-Agent -Endpoint $Ep -AgentArgs $a -TimeoutSec 90
}

function Get-ServerCertSha256 {
    $c = Get-ChildItem 'Cert:\LocalMachine\Remote Desktop' -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $c) { return $null }
    return [Convert]::ToHexString([System.Security.Cryptography.SHA256]::HashData($c.RawData)).ToLower()
}

function Stop-AllAgents {
    foreach ($ep in @($script:Daemons.Keys)) { Stop-AgentDaemon -Endpoint $ep | Out-Null }
    Get-Process -Name 'ironrdp-agent' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
}

$script:EpMain = '\\.\pipe\spike-main'; $script:EpCtl = '\\.\pipe\spike-ctl'; $script:EpIdle = '\\.\pipe\spike-idle'
$script:EpPp = '\\.\pipe\spike-pp'; $script:EpKill = '\\.\pipe\spike-kill'

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

Invoke-Phase 'P0b help output and capability probe (items 4-7, 9, 12)' {
    $dir = Join-Path $ArtifactsDir 'help'
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $cmds = @(@('--help'), @('--help-agent'), @('connect', '--help'), @('daemon-start', '--help'), @('status', '--help'), @('screenshot', '--help'),
        @('mouse-move', '--help'), @('mouse-button', '--help'), @('wheel', '--help'), @('key-scancode', '--help'), @('key-unicode', '--help'),
        @('disconnect', '--help'), @('query-props', '--help'), @('query-logs', '--help'), @('resize', '--help'),
        @('type-unicode', '--help'), @('wait-frame', '--help'), @('cert', '--help'), @('daemon', '--help'), @('session', '--help'))
    foreach ($c in $cmds) {
        $r = Invoke-Agent -NoEndpoint -AgentArgs $c -TimeoutSec 20
        $name = ($c -join '_').Replace('-', '')
        [System.IO.File]::WriteAllText((Join-Path $dir "$name.txt"), ("exit=$($r.ExitCode)`n" + $r.Out + $r.Err), $script:Utf8)
    }
    $top = (Invoke-Agent -NoEndpoint -AgentArgs @('--help')).Out
    $script:Caps.TypeUnicode = ($top -match '(?m)^\s+type-unicode\b')
    $script:Caps.WaitFrame = ($top -match '(?m)^\s+wait-frame\b')
    $script:Caps.Cert = ($top -match '(?m)^\s+cert\b')
    $script:Caps.DaemonStop = ($top -match '(?m)^\s+daemon\b')
    $script:Caps.Session = ($top -match '(?m)^\s+session\b')
    $connectHelp = [System.IO.File]::ReadAllText((Join-Path $dir 'connect_help.txt'))
    $daemonHelp = [System.IO.File]::ReadAllText((Join-Path $dir 'daemonstart_help.txt'))
    Rec '12' 'binary version and command set (release 0.1.0 vs upstream master README)' 'info' ("commands in --help: " + (($top -split "`n" | Where-Object { $_ -match '^\s{2}[a-z]' } | ForEach-Object { ($_.Trim() -split '\s+')[0] }) -join ', '))
    Rec '4' 'type-unicode command (bounded 96-char bulk input) exists in this release' $(if ($script:Caps.TypeUnicode) { 'pass' } else { 'fail' }) $(if ($script:Caps.TypeUnicode) { 'present' } else { 'absent from --help; only key-unicode --char C --pressed true|false (one character, one key event per call) exists' })
    Rec '5' 'wait-frame / frame counter exists in this release' $(if ($script:Caps.WaitFrame) { 'pass' } else { 'fail' }) $(if ($script:Caps.WaitFrame) { 'present' } else { 'absent from --help (status has no frame number either)' })
    Rec '6' 'cert trust / known-certificates store and --skip-certificate-check exist in this release' $(if ($script:Caps.Cert -or $daemonHelp -match 'skip-certificate') { 'pass' } else { 'fail' }) "cert subcommand present=$($script:Caps.Cert); daemon-start --skip-certificate-check present=$($daemonHelp -match 'skip-certificate'); see help/ and source: https://github.com/Devolutions/IronRDP/blob/ironrdp-agent-v0.1.0/crates/ironrdp-tls/src/rustls.rs (NoCertificateVerification)"
    Rec '9' 'graceful daemon stop (daemon stop) exists in this release' $(if ($script:Caps.DaemonStop) { 'pass' } else { 'fail' }) $(if ($script:Caps.DaemonStop) { 'present' } else { 'absent: only a foreground daemon-start; stopping means killing the process' })
    $hits = [regex]::Matches($connectHelp, '(?im)^.*(stdin|password-file|password-stdin|--password|RDP_PASSWORD|env).*$') | ForEach-Object { $_.Value.Trim() }
    Rec '7' 'connect --help: credential input paths' 'info' ("lines mentioning password/stdin/env: " + ($hits -join ' || '))
    $stdinFlag = $connectHelp -match '(?i)password-stdin|password-file|--stdin|RDP_PASSWORD'
    Rec '7' 'dedicated stdin/file/env password input on connect exists' $(if ($stdinFlag) { 'pass' } else { 'fail' }) $(if ($stdinFlag) { 'present in connect --help' } else { 'none: connect only has -p/--password (argv). Out-of-argv path is daemon-start --overlay FILE containing ClearTextPassword (plaintext file), tested below' })
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
Invoke-Phase 'P2 NLA connect, certificate handling (items 1, 2, 6)' {
    $ep = $script:EpMain
    $d = Start-AgentDaemon -Endpoint $ep -User "$script:Computer\rdpspike1" -Password $script:Pw1
    Rec '9' 'daemon-start (foreground process, detached by the harness) with --overlay answers status' $(if ($d.Up) { 'pass' } else { 'fail' }) "pid=$($d.Pid) status: $($d.Status)"
    $c1 = Connect-Rdp -Ep $ep -Server '127.0.0.1:3389' -User 'rdpspike1'
    Save-Text 'connect1.txt' (Format-AgentResult $c1)
    Rec '2' 'connect command output / exit code' 'info' (Format-AgentResult $c1)
    $s1 = Wait-AgentConnected -Endpoint $ep -TimeoutSec 90 -Label 'c1-nla'
    $ok = ($s1.State -eq 'Connected')
    Rec '2' "ironrdp-agent connects to 127.0.0.1:3389 (server NLA=$Nla, client CredSSP default) with the overlay password" $(if ($ok) { 'pass' } else { 'fail' }) "state=$($s1.State) $($s1.Width)x$($s1.Height); status: $($s1.Text.Trim())"
    $script:MainConnected = $ok
    Rec '6' 'strict TLS validation by default: a self-signed server certificate must be refused without a pin' $(if ($ok) { 'fail' } else { 'info' }) "connected with no pin and no trust store: $ok. Source: ironrdp-tls uses rustls NoCertificateVerification at the 0.1.0 tag. Server cert SHA-256 (Windows store) = $(Get-ServerCertSha256). Status: $($s1.Text.Trim())"
    $ql = Invoke-Agent -Endpoint $ep -AgentArgs @('query-logs')
    Save-Text 'session-log-main.txt' ($ql.Out + $ql.Err)
    $lines = ($ql.Out -split "`n" | Where-Object { $_ -match '(?i)credssp|nla|hybrid|ntlm|kerberos|security protocol|selected protocol|certificate|tls' } | Select-Object -First 14) -join ' || '
    Rec '2' 'session log lines about security protocol / certificate' 'info' $lines
    try {
        $ev = Get-WinEvent -FilterHashtable @{ LogName = 'Microsoft-Windows-TerminalServices-RemoteConnectionManager/Operational'; Id = 1149 } -MaxEvents 5 -ErrorAction Stop | ForEach-Object { $_.Message }
        Save-Text 'event-1149.txt' ($ev -join "`n---`n")
    } catch { Save-Text 'event-1149.txt' "none: $($_.Exception.Message)" }
    try {
        $ev = Get-WinEvent -FilterHashtable @{ LogName = 'Security'; Id = 4624 } -MaxEvents 80 -ErrorAction Stop | Where-Object { $_.Message -match 'rdpspike' -and $_.Message -match 'Logon Type:\s+(10|3)' } |
            Select-Object -First 4 | ForEach-Object { $_.Message }
        Save-Text 'event-4624.txt' ($ev -join "`n---`n")
    } catch { Save-Text 'event-4624.txt' "none: $($_.Exception.Message)" }

    # Negative control: a client that does not offer CredSSP must be refused when the server requires NLA.
    $ep3 = $script:EpCtl
    [void](Start-AgentDaemon -Endpoint $ep3 -User "$script:Computer\rdpspike3" -Password $script:Pw3)
    [void](Connect-Rdp -Ep $ep3 -Server '127.0.0.1:3389' -User 'rdpspike3' -Extra @('--prop', 'enablecredsspsupport:i:0'))
    $sn = Wait-AgentConnected -Endpoint $ep3 -TimeoutSec 40 -Label 'ctl-no-credssp'
    if ($Nla -eq 1) {
        Rec '2' 'control: client with CredSSP disabled is refused by an NLA-required server (proves NLA is enforced and used)' $(if ($sn.State -ne 'Connected') { 'pass' } else { 'fail' }) "state=$($sn.State); status: $($sn.Text.Trim())"
    } else {
        Rec '2' 'control (server NLA off): client with CredSSP disabled connects' $(if ($sn.State -eq 'Connected') { 'pass' } else { 'fail' }) "state=$($sn.State); status: $($sn.Text.Trim())"
    }
    Invoke-Agent -Endpoint $ep3 -AgentArgs @('disconnect') | Out-Null
    Start-Sleep -Seconds 1
    Stop-SpikeSessions -OnlyUser 'rdpspike3'
    Stop-AgentDaemon -Endpoint $ep3 | Out-Null
    # Wrong password: how is it reported?
    [void](Start-AgentDaemon -Endpoint $ep3 -User "$script:Computer\rdpspike3" -Password 'Wrong-Passw0rd!x')
    [void](Connect-Rdp -Ep $ep3 -Server '127.0.0.1:3389' -User 'rdpspike3')
    $sw = Wait-AgentConnected -Endpoint $ep3 -TimeoutSec 40 -Label 'ctl-wrong-password'
    Rec '2' 'wrong password: connection fails and the status says so' $(if ($sw.State -ne 'Connected') { 'pass' } else { 'fail' }) "state=$($sw.State); status: $($sw.Text.Trim())"
    Stop-AgentDaemon -Endpoint $ep3 | Out-Null
}

# ============================================================================================
Invoke-Phase 'P3 screenshot (item 3) and credential exposure (item 7)' {
    if (-not $script:MainConnected) { Rec '3' 'screenshot' 'unavailable' 'main session not connected'; return }
    $ep = $script:EpMain
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
    $procs = Get-AgentProcessCommandLines
    Save-Text 'agent-processes.txt' ($procs | ForEach-Object { "pid=$($_.Pid) ppid=$($_.ParentPid) cmd=$($_.CommandLine)" })
    $leak = @($procs | Where-Object { $_.CommandLine -and (($_.CommandLine -like "*$script:Pw1*") -or ($_.CommandLine -like "*$script:Pw3*")) })
    Rec '7' 'overlay path: password absent from the command line of the live ironrdp-agent daemon process' $(if ($leak.Count -eq 0 -and $procs.Count -gt 0) { 'pass' } else { 'fail' }) "processes=$($procs.Count) leakCount=$($leak.Count); cmdlines (secrets masked): $(($procs | ForEach-Object { Protect-Text $_.CommandLine }) -join ' || ')"
    $st = Get-AgentStatus -Endpoint $ep
    Rec '7' 'status reports credentials loaded from the overlay' 'info' ($st.Text.Trim())
    $ov = $script:Daemons[$ep].Overlay
    $ovExists = [bool]($ov -and (Test-Path $ov))
    Rec '7' 'overlay path: the password sits in plaintext in the overlay file for the daemon lifetime (at-rest exposure)' 'info' "overlay file exists=$ovExists path=$ov; content is 'username:s:...' and 'ClearTextPassword:s:<password>' lines"
    $props = Invoke-Agent -Endpoint $ep -AgentArgs @('query-props')
    $pl = ($props.Out + $props.Err)
    Save-Text 'props-main.txt' $pl
    Rec '7' 'query-props / query-logs output does not echo the password' $(if (($pl -notlike "*$script:Pw1*") -and (((Invoke-Agent -Endpoint $ep -AgentArgs @('query-logs')).Out) -notlike "*$script:Pw1*")) { 'pass' } else { 'fail' }) 'checked output of query-props and query-logs'
    $paths = @("$env:LOCALAPPDATA\ironrdp-agent", "$env:APPDATA\ironrdp-agent")
    $found = @(); $listing = @()
    foreach ($p in $paths) { if (Test-Path $p) { Get-ChildItem $p -Recurse -File -ErrorAction SilentlyContinue | ForEach-Object { $listing += "$($_.FullName) $($_.Length)"; $txt = [System.IO.File]::ReadAllText($_.FullName); foreach ($pw in @($script:Pw1, $script:Pw2, $script:Pw3)) { if ($txt.Contains($pw)) { $found += $_.FullName } } } } }
    Save-Text 'agent-state-files.txt' ($listing -join "`n")
    Rec '7' 'agent writes no state files containing the password (LOCALAPPDATA / APPDATA ironrdp-agent)' $(if ($found.Count -eq 0) { 'pass' } else { 'fail' }) "files=$($listing.Count) containing password=[$($found -join ', ')]"
    $psi = [System.Diagnostics.ProcessStartInfo]::new($script:AgentExe)
    foreach ($a in @('--endpoint', $ep, 'connect', '--server', '127.0.0.1:3389', '-u', "$script:Computer\rdpspike1", '-p', $script:Pw1)) { $psi.ArgumentList.Add($a) }
    $psi.UseShellExecute = $false; $psi.CreateNoWindow = $true; $psi.RedirectStandardOutput = $true; $psi.RedirectStandardError = $true
    $p = [System.Diagnostics.Process]::Start($psi)
    $seen = $null
    for ($i = 0; $i -lt 60 -and -not $p.HasExited; $i++) {
        $w = Get-CimInstance Win32_Process -Filter "ProcessId=$($p.Id)" -ErrorAction SilentlyContinue
        if ($w) { $seen = $w.CommandLine; break }
    }
    $null = $p.WaitForExit(20000)
    $vis = ($seen -and $seen -like "*$script:Pw1*")
    Rec '7' 'argv path: connect -p PASSWORD puts the password in the CLI process command line (observed via Win32_Process)' $(if ($vis) { 'fail' } elseif ($seen) { 'pass' } else { 'info' }) "observed=$([bool]$seen) containsPassword=$vis cmdline=$(Protect-Text $seen). (fail = exposed; info = CLI exited before it could be sampled; by clap definition -p is argv)"
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

# Types text with key-unicode, one Unicode scalar per press+release pair (the only text input command in 0.1.0).
function Test-TypeCase {
    param([string] $User, [string] $Ep, [string] $Text)
    [void](Reset-Receiver -User $User)
    $f = Ensure-InputFocus -User $User -Ep $Ep
    $bad = @(); $n = 0
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    for ($i = 0; $i -lt $Text.Length; $i++) {
        $ch = [string]$Text[$i]
        if ([char]::IsHighSurrogate($Text[$i]) -and ($i + 1) -lt $Text.Length) { $i++; $ch += $Text[$i] }
        $d = Invoke-Agent -Endpoint $Ep -AgentArgs @('key-unicode', '--char', $ch, '--pressed', 'true') -TimeoutSec 30
        $u = Invoke-Agent -Endpoint $Ep -AgentArgs @('key-unicode', '--char', $ch, '--pressed', 'false') -TimeoutSec 30
        if ($d.ExitCode -ne 0 -or $u.ExitCode -ne 0) { if ($bad.Count -lt 5) { $bad += "char#$n '$ch' down:$($d.ExitCode) $($d.Err.Trim()) up:$($u.ExitCode) $($u.Err.Trim())" } else { $bad += '.' } }
        $n++
    }
    $sw.Stop()
    $exp = $script:Utf8.GetBytes($Text)
    $w = Wait-ReceiverStable -User $User -Expected $exp
    $cmp = Compare-Text -Expected $Text -ActualBytes $w.Bytes
    return [pscustomobject]@{ Equal = $cmp.Equal; Detail = "scalars=$n cliCalls=$($n * 2) sendMs=$($sw.ElapsedMilliseconds) settleMs=$($w.SettleMs) cliFailures=$($bad.Count) [$($bad -join '; ')] focus='$f' $($cmp.Detail)" }
}

Invoke-Phase 'P4 input: key-unicode text entry (item 4)' {
    if (-not $script:MainConnected -or -not $script:Rx1) { Rec '4' 'input tests' 'unavailable' 'no session or receiver'; return }
    $u = 'rdpspike1'; $ep = $script:EpMain
    Rec '4' 'test string facts' 'info' "unit scalars=$(Get-ScalarCount $script:Unit) utf16=$($script:Unit.Length)"
    $r = Test-TypeCase -User $u -Ep $ep -Text $script:Unit
    Rec '4' 'key-unicode: mixed ASCII/Hangul/symbols string (<=96 chars), one char per call, byte-exact at target' $(if ($r.Equal) { 'pass' } else { 'fail' }) $r.Detail
    $mk = { param($n) $sb = [System.Text.StringBuilder]::new(); for ($i = 1; $i -le $n; $i++) { [void]$sb.Append(('[{0:0000}] ' -f $i)).Append($script:Unit).Append(' ') }; $sb.ToString() }
    $long = & $mk 6
    $okc = 0; $fails = @()
    for ($it = 1; $it -le 2; $it++) {
        $r = Test-TypeCase -User $u -Ep $ep -Text $long
        if ($r.Equal) { $okc++ } else { $fails += "iter$it : $($r.Detail)" }
    }
    Rec '4' "key-unicode: ~$($long.Length)-char mixed text (beyond 96), 2 repetitions, byte-exact" $(if ($okc -eq 2) { 'pass' } else { 'fail' }) "passed $okc/2. $($fails -join ' ### ')"
    $astral = 'A' + [char]::ConvertFromUtf32(0x1F600) + 'B'
    $r = Test-TypeCase -User $u -Ep $ep -Text $astral
    Rec '4' 'key-unicode: character outside the BMP (U+1F600)' 'info' "equal=$($r.Equal) $($r.Detail)"
    $r = Test-TypeCase -User $u -Ep $ep -Text "line1`nline2"
    $b = Get-ReceiverBytes -User $u
    Rec '4' 'key-unicode: embedded LF character' 'info' "equal=$($r.Equal) receivedHex=$([Convert]::ToHexString($b)) $($r.Detail)"
    $r = Test-TypeCase -User $u -Ep $ep -Text "a`tb"
    $b = Get-ReceiverBytes -User $u
    Rec '4' 'key-unicode: embedded TAB character' 'info' "equal=$($r.Equal) receivedHex=$([Convert]::ToHexString($b)) $($r.Detail)"
    $png2 = Join-Path $ArtifactsDir 'screenshot-main-2.png'
    Invoke-Agent -Endpoint $ep -AgentArgs @('screenshot', $png2) | Out-Null
    $d = [RdpSpikeImage]::DiffPixels((Join-Path $ArtifactsDir 'screenshot-main-1.png'), $png2)
    Rec '3' 'screenshot reflects screen changes after input (pixel diff vs first screenshot)' $(if ($d -gt 100) { 'pass' } else { 'fail' }) "sampledDiffPixels=$d"
}

Invoke-Phase 'P5 input: key-scancode, mouse, wheel (item 4)' {
    if (-not $script:MainConnected -or -not $script:Rx1) { Rec '4' 'key/mouse tests' 'unavailable' 'no session or receiver'; return }
    $u = 'rdpspike1'; $ep = $script:EpMain
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
    $u = 'rdpspike1'; $ep = $script:EpMain
    [void](Reset-Receiver -User $u); [void](Ensure-InputFocus -User $u -Ep $ep)
    $t0 = [DateTime]::UtcNow
    $r = Invoke-Agent -Endpoint $ep -AgentArgs @('key-unicode', '--char', 'Q', '--pressed', 'true')
    $r2 = Invoke-Agent -Endpoint $ep -AgentArgs @('key-unicode', '--char', 'Q', '--pressed', 'false')
    $tRet = $r2.EndedUtc
    $tEff = $null
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.ElapsedMilliseconds -lt 5000) { if ((Get-ReceiverBytes -User $u).Length -ge 1) { $tEff = [DateTime]::UtcNow; break }; Start-Sleep -Milliseconds 5 }
    $lag = if ($tEff) { [int](($tEff - $tRet).TotalMilliseconds) } else { -1 }
    Rec '5' 'key-unicode success output and exit code' 'info' ((Format-AgentResult $r) + ' || ' + (Format-AgentResult $r2))
    Rec '5' 'time between CLI return and the target seeing the character (negative = target saw it first; -1 = not seen in 5 s)' 'info' "cliMs=$($r.Ms)+$($r2.Ms) cliReturnToTargetSeenMs=$lag"
    foreach ($cmd in @(@('mouse-move', '--x', '10', '--y', '10'), @('mouse-button', '--button', 'left', '--pressed', 'false'), @('wheel', '--delta', '120'), @('key-scancode', '--scancode', '0x2A', '--pressed', 'false'), @('status'))) {
        $x = Invoke-Agent -Endpoint $ep -AgentArgs $cmd
        Rec '5' "output of '$($cmd -join ' ')'" 'info' (Format-AgentResult $x)
    }
    Rec '5' 'invalid input is rejected with a message (key-unicode --char with two characters)' 'info' (Format-AgentResult (Invoke-Agent -Endpoint $ep -AgentArgs @('key-unicode', '--char', 'ab', '--pressed', 'true')))
    [void](Reset-Receiver -User $u); [void](Ensure-InputFocus -User $u -Ep $ep)
    $exe = $script:AgentExe
    $n = 200
    $res = 1..$n | ForEach-Object -ThrottleLimit 50 -Parallel {
        $ch = [string][char](97 + ($_ % 26))
        $o1 = & $using:exe --endpoint $using:ep key-unicode --char $ch --pressed true 2>&1 | Out-String
        $c1 = $LASTEXITCODE
        $o2 = & $using:exe --endpoint $using:ep key-unicode --char $ch --pressed false 2>&1 | Out-String
        [pscustomobject]@{ Ch = $ch; Exit = $c1; Exit2 = $LASTEXITCODE; Out = ($o1 + $o2).Trim() }
    }
    $acc = @($res | Where-Object { $_.Exit -eq 0 -and $_.Exit2 -eq 0 }); $rej = @($res | Where-Object { $_.Exit -ne 0 -or $_.Exit2 -ne 0 })
    $w = Wait-ReceiverStable -User $u -QuietMs 4000 -MaxSec 120
    $got = $script:Utf8.GetString($w.Bytes)
    $diff = @()
    foreach ($c in 97..122) {
        $ch = [string][char]$c
        $exp = @($acc | Where-Object { $_.Ch -eq $ch }).Count
        $act = @($got.ToCharArray() | Where-Object { $_ -eq $ch[0] }).Count
        if ($exp -ne $act) { $diff += "$ch expected=$exp got=$act" }
    }
    $rejMsgs = ($rej | Group-Object { $_.Out } | ForEach-Object { "x$($_.Count): $($_.Name)" }) -join ' || '
    Rec '5' "parallel flood: $n concurrent key-unicode press+release pairs; accepted=$($acc.Count) rejected=$($rej.Count)" 'info' "rejection messages: $rejMsgs"
    Rec '5' 'every call reported as success was delivered (per-letter counts equal; nothing extra)' $(if ($diff.Count -eq 0 -and $got.Length -eq $acc.Count) { 'pass' } else { 'fail' }) "accepted=$($acc.Count) receivedLen=$($got.Length) mismatches=[$($diff -join '; ')]"
    Save-Text 'flood-received.txt' $got
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
    $epA = $script:EpMain; $epB = $script:EpCtl
    [void](Start-AgentDaemon -Endpoint $epB -User "$script:Computer\rdpspike3" -Password $script:Pw3)
    [void](Connect-Rdp -Ep $epB -Server '127.0.0.1:3389' -User 'rdpspike3')
    $sB = Wait-AgentConnected -Endpoint $epB -TimeoutSec 90 -Label 'second-session'
    if ($sB.State -ne 'Connected') {
        Rec '9' 'two simultaneous sessions via --endpoint' 'fail' "second session not connected: $($sB.Text.Trim())"
    } else {
        $rxB = Wait-ReceiverReady -User 'rdpspike3' -TimeoutSec 150
        if ($rxB) {
            [void](Reset-Receiver -User 'rdpspike1'); [void](Reset-Receiver -User 'rdpspike3')
            [void](Ensure-InputFocus -User 'rdpspike1' -Ep $epA); [void](Ensure-InputFocus -User 'rdpspike3' -Ep $epB)
            $ta = 'session-A ' + [regex]::Unescape('\ud55c'); $tb = 'session-B ' + [regex]::Unescape('\uae00') + ' x'
            $exe = $script:AgentExe
            $pairs = @(@($epA, $ta), @($epB, $tb))
            $pairs | ForEach-Object -ThrottleLimit 2 -Parallel {
                $ep = $_[0]; $text = $_[1]
                for ($i = 0; $i -lt $text.Length; $i++) {
                    $ch = [string]$text[$i]
                    & $using:exe --endpoint $ep key-unicode --char $ch --pressed true | Out-Null
                    & $using:exe --endpoint $ep key-unicode --char $ch --pressed false | Out-Null
                }
            }
            $wa = Wait-ReceiverStable -User 'rdpspike1' -Expected $script:Utf8.GetBytes($ta)
            $wb = Wait-ReceiverStable -User 'rdpspike3' -Expected $script:Utf8.GetBytes($tb)
            $ca = Compare-Text $ta $wa.Bytes; $cb = Compare-Text $tb $wb.Bytes
            Rec '9' 'two daemons (--endpoint) drive two RDP sessions at the same time; each target receives only its own text' $(if ($ca.Equal -and $cb.Equal) { 'pass' } else { 'fail' }) "A: $($ca.Detail) | B: $($cb.Detail)"
            $pa = Join-Path $ArtifactsDir 'screenshot-A.png'; $pb = Join-Path $ArtifactsDir 'screenshot-B.png'
            Invoke-Agent -Endpoint $epA -AgentArgs @('screenshot', $pa) | Out-Null; Invoke-Agent -Endpoint $epB -AgentArgs @('screenshot', $pb) | Out-Null
            Rec '9' 'independent screenshots from the two sessions' $(if ((Test-Png $pa).Valid -and (Test-Png $pb).Valid) { 'pass' } else { 'fail' }) "A: $((Test-Png $pa).Text) | B: $((Test-Png $pb).Text)"
        } else { Rec '9' 'two sessions: second receiver' 'fail' 'receiver of session B did not start' }
    }
    $procs = Get-AgentProcessCommandLines
    Rec '9' 'agent processes while two sessions are live' 'info' (($procs | ForEach-Object { "pid=$($_.Pid) ppid=$($_.ParentPid) cmd=$(Protect-Text $_.CommandLine)" }) -join ' || ')
    Rec '9' 'named pipes while live' 'info' ((Get-PipeNames | Where-Object { $_ -match 'spike|ironrdp' }) -join ', ')

    $d = Invoke-Agent -Endpoint $epB -AgentArgs @('disconnect')
    $stB = Invoke-Agent -Endpoint $epB -AgentArgs @('status')
    Rec '9' 'disconnect output and status afterwards (the daemon is expected to stay up)' 'info' "disconnect: $(Format-AgentResult $d) | status: $(Format-AgentResult $stB)"
    $sess = (Get-RdpSessions | ForEach-Object { "$($_.User)#$($_.Id)=$($_.State)" }) -join ', '
    Rec '9' 'server-side session state after client disconnect' 'info' $sess
    Stop-AgentDaemon -Endpoint $epB | Out-Null
    Start-Sleep -Seconds 2
    $pipesB = @(Get-PipeNames | Where-Object { $_ -match 'spike-ctl' })
    Rec '9' 'killing a daemon (no graceful stop command exists) leaves no pipe for that endpoint' $(if ($pipesB.Count -eq 0) { 'pass' } else { 'fail' }) "leftoverPipes=$($pipesB -join ',')"
    $sa = Get-AgentStatus -Endpoint $epA
    Rec '9' 'stopping daemon B does not disturb session A' $(if ($sa.State -eq 'Connected') { 'pass' } else { 'fail' }) "A state=$($sa.State)"
    $dA = Invoke-Agent -Endpoint $epA -AgentArgs @('disconnect')
    Stop-AgentDaemon -Endpoint $epA | Out-Null
    Start-Sleep -Seconds 2
    $all = @(Get-Process -Name 'ironrdp-agent' -ErrorAction SilentlyContinue)
    $allPipes = @(Get-PipeNames | Where-Object { $_ -match 'spike|ironrdp' })
    Rec '9' 'after disconnect + killing every daemon: no ironrdp-agent process, no IPC pipe left' $(if ($all.Count -eq 0 -and $allPipes.Count -eq 0) { 'pass' } else { 'fail' }) "processes=$($all.Count) pipes=[$($allPipes -join ',')] A disconnect: $(Format-AgentResult $dA)"
    $files = ((@("$env:LOCALAPPDATA\ironrdp-agent", "$env:APPDATA\ironrdp-agent") | ForEach-Object { if (Test-Path $_) { Get-ChildItem $_ -Recurse -File | ForEach-Object { "$($_.FullName) $($_.Length)" } } }) -join '; ')
    Rec '9' 'files the agent leaves on disk' 'info' $(if ($files) { $files } else { '(none)' })
    Stop-SpikeSessions
}

# ============================================================================================
Invoke-Phase 'P8 daemon without session, server-side logoff, daemon kill (items 5, 9)' {
    $idle = $script:EpIdle
    [void](Start-AgentDaemon -Endpoint $idle)
    $out = @()
    foreach ($cmd in @(@('status'), @('mouse-move', '--x', '5', '--y', '5'), @('key-unicode', '--char', 'x', '--pressed', 'true'), @('screenshot', (Join-Path $ArtifactsDir 'idle.png')), @('disconnect'))) {
        $x = Invoke-Agent -Endpoint $idle -AgentArgs $cmd
        $out += "[$($cmd -join ' ')] $(Format-AgentResult $x)"
    }
    Rec '5' 'daemon with no session: status and every input command give an explicit error' 'info' ($out -join "`n")
    $noDaemon = Invoke-Agent -Endpoint '\\.\pipe\spike-nonexistent' -AgentArgs @('status')
    Rec '5' 'no daemon at the endpoint: CLI error text and exit code' 'info' (Format-AgentResult $noDaemon)
    Stop-AgentDaemon -Endpoint $idle | Out-Null

    $ep = $script:EpKill
    [void](Start-AgentDaemon -Endpoint $ep -User "$script:Computer\rdpspike2" -Password $script:Pw2)
    [void](Connect-Rdp -Ep $ep -Server '127.0.0.1:3389' -User 'rdpspike2')
    $s = Wait-AgentConnected -Endpoint $ep -TimeoutSec 90 -Label 'kill-connect'
    if ($s.State -ne 'Connected') { Rec '5' 'reconnect for disconnect tests' 'fail' "state=$($s.State) $($s.Text.Trim())"; return }
    $sid = (Get-RdpSessions | Where-Object { $_.User -eq 'rdpspike2' } | Select-Object -First 1).Id
    $t0 = Get-Date
    & logoff.exe $sid 2>&1 | Out-Null
    $trace = @(); $lastKey = ''
    while (((Get-Date) - $t0).TotalSeconds -lt 30) {
        $x = Get-AgentStatus -Endpoint $ep
        $key = "$($x.Exit)|$($x.State)"
        if ($key -ne $lastKey) { $trace += ("+{0:0.0}s exit={1} state={2} | {3}" -f ((Get-Date) - $t0).TotalSeconds, $x.Exit, $x.State, ($x.Text.Trim() -replace '\s+', ' ')); $lastKey = $key }
        if ($x.State -ne 'Connected') { Start-Sleep -Seconds 2; $x2 = Get-AgentStatus -Endpoint $ep; $trace += "later: state=$($x2.State) | $(($x2.Text.Trim() -replace '\s+', ' '))"; break }
        Start-Sleep -Milliseconds 500
    }
    Rec '5' 'server logs the session off: how status reports it (time to notice and text)' 'info' ($trace -join "`n")
    $out = @()
    foreach ($cmd in @(@('mouse-move', '--x', '5', '--y', '5'), @('mouse-button', '--button', 'left', '--pressed', 'false'), @('key-scancode', '--scancode', '0x1E', '--pressed', 'true'), @('key-unicode', '--char', 'x', '--pressed', 'true'), @('wheel', '--delta', '120'), @('screenshot', (Join-Path $ArtifactsDir 'after-logoff.png')), @('disconnect'), @('status'))) {
        $x = Invoke-Agent -Endpoint $ep -AgentArgs $cmd
        $out += "[$($cmd -join ' ')] $(Format-AgentResult $x)"
    }
    Rec '5' 'after the session is gone: each command (exit code and text)' 'info' ($out -join "`n")
    Save-Text 'after-logoff-commands.txt' ($out -join "`n")
    [void](Connect-Rdp -Ep $ep -Server '127.0.0.1:3389' -User 'rdpspike2')
    $sr = Wait-AgentConnected -Endpoint $ep -TimeoutSec 90 -Label 'kill-reconnect-same-daemon'
    Rec '9' 'the same daemon can open a new session after the server ended the previous one' $(if ($sr.State -eq 'Connected') { 'pass' } else { 'fail' }) "state=$($sr.State) $($sr.Text.Trim())"
    $pipesBefore = @(Get-PipeNames | Where-Object { $_ -match 'spike|ironrdp' })
    $dpid = $script:Daemons[$ep].Pid
    Stop-Process -Id $dpid -Force
    Start-Sleep -Seconds 3
    $procAfter = @(Get-Process -Name 'ironrdp-agent' -ErrorAction SilentlyContinue)
    $pipesAfter = @(Get-PipeNames | Where-Object { $_ -match 'spike|ironrdp' })
    $stat = Invoke-Agent -Endpoint $ep -AgentArgs @('status')
    $mm = Invoke-Agent -Endpoint $ep -AgentArgs @('mouse-move', '--x', '1', '--y', '1')
    $sess = (Get-RdpSessions | ForEach-Object { "$($_.User)#$($_.Id)=$($_.State)" }) -join ', '
    $leftFiles = ((@("$env:LOCALAPPDATA\ironrdp-agent", "$env:APPDATA\ironrdp-agent") | ForEach-Object { if (Test-Path $_) { Get-ChildItem $_ -Recurse -File | ForEach-Object { "$($_.Name) $($_.Length)" } } }) -join '; ')
    Rec '9' 'daemon killed (Stop-Process -Force) while connected: what is left' 'info' "daemon pid=$dpid processesAfter=$($procAfter.Count) pipesBefore=[$($pipesBefore -join ',')] pipesAfter=[$($pipesAfter -join ',')] | status: $(Format-AgentResult $stat) | mouse-move: $(Format-AgentResult $mm) | server sessions after kill: $sess | files: $leftFiles"
    Stop-AgentDaemon -Endpoint $ep | Out-Null
    [void](Start-AgentDaemon -Endpoint $ep -User "$script:Computer\rdpspike2" -Password $script:Pw2 -Tag 'restart')
    [void](Connect-Rdp -Ep $ep -Server '127.0.0.1:3389' -User 'rdpspike2')
    $s = Wait-AgentConnected -Endpoint $ep -TimeoutSec 90 -Label 'kill-restart'
    Rec '9' 'after the daemon was killed, a new daemon on the same endpoint connects again' $(if ($s.State -eq 'Connected') { 'pass' } else { 'fail' }) "state=$($s.State) $($s.Text.Trim())"
    Invoke-Agent -Endpoint $ep -AgentArgs @('disconnect') | Out-Null
    Stop-AgentDaemon -Endpoint $ep | Out-Null
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
        $ep = $script:EpPp
        [void](Start-AgentDaemon -Endpoint $ep -User "$script:Computer\rdpspike2" -Password $script:Pw2 -Tag $variant)
        $srv = "127.0.0.1:$port"
        [void](Connect-Rdp -Ep $ep -Server $srv -User 'rdpspike2')
        $s = Wait-AgentConnected -Endpoint $ep -TimeoutSec 90 -Label "pp-$variant"
        $ok = ($s.State -eq 'Connected')
        Rec '8' "$variant : connect through 127.0.0.1:$port -> 3389 (NLA=$Nla)" $(if ($ok) { 'pass' } else { 'fail' }) "state=$($s.State) $($s.Width)x$($s.Height); status: $($s.Text.Trim())"
        if ($ok) {
            $rx = Wait-ReceiverReady -User 'rdpspike2' -TimeoutSec 150
            $png = Join-Path $ArtifactsDir "screenshot-$variant.png"
            Invoke-Agent -Endpoint $ep -AgentArgs @('screenshot', $png) | Out-Null
            Rec '8' "$variant : screenshot through the forwarded port" $(if ((Test-Png $png).Valid) { 'pass' } else { 'fail' }) (Test-Png $png).Text
            if ($rx) {
                $t = Test-TypeCase -User 'rdpspike2' -Ep $ep -Text ('pp ' + [regex]::Unescape('\ud55c\uae00') + ' ok!')
                Rec '8' "$variant : key-unicode input through the forwarded port is byte-exact" $(if ($t.Equal) { 'pass' } else { 'fail' }) $t.Detail
            }
        }
        Invoke-Agent -Endpoint $ep -AgentArgs @('disconnect') | Out-Null
        Stop-AgentDaemon -Endpoint $ep | Out-Null
        Stop-SpikeSessions -OnlyUser 'rdpspike2'
        if ($variant -eq 'portproxy') { & netsh.exe interface portproxy delete v4tov4 listenaddress=127.0.0.1 listenport=$port | Out-Null }
        if ($relay) { $relay.Stop() }
    }
}

Invoke-Phase 'P10 NLA without the autologon property (design information)' {
    $ep = $script:EpIdle
    [void](Start-AgentDaemon -Endpoint $ep -User "$script:Computer\rdpspike3" -Password $script:Pw3 -Tag 'noauto')
    $props = @('--prop', 'desktopwidth:i:1280', '--prop', 'desktopheight:i:800')
    [void](Connect-Rdp -Ep $ep -Server '127.0.0.1:3389' -User 'rdpspike3' -Props $props)
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
    Stop-AgentDaemon -Endpoint $ep | Out-Null
    Stop-SpikeSessions
}

Stop-AllAgents
Stop-SpikeSessions
if (Test-Path $script:OverlayDir) { Remove-Item -Recurse -Force $script:OverlayDir -ErrorAction SilentlyContinue }
Copy-Item -Path 'C:\rdp-spike\recv' -Destination (Join-Path $ArtifactsDir 'receiver-logs') -Recurse -Force -ErrorAction SilentlyContinue
$summary = $script:Results | Group-Object verdict | ForEach-Object { "$($_.Name)=$($_.Count)" }
Write-Log "DONE nla=$Nla results: $($summary -join ' ')"
$script:Results | ConvertTo-Json -Depth 4 | Set-Content -Path (Join-Path $ArtifactsDir 'results.json') -Encoding utf8
exit 0
