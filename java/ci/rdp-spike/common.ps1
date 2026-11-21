# Shared helpers for the ironrdp-agent adoption spike (Windows runner).
# Dot-source this file. All state lives in $script: variables set by Initialize-Spike.

$script:Results = [System.Collections.Generic.List[object]]::new()
$script:Secrets = [System.Collections.Generic.List[string]]::new()
$script:AgentExe = $null
$script:OverlayDir = Join-Path ([System.IO.Path]::GetTempPath()) 'rdp-spike-overlay'
$script:ArtDir = $null
$script:LogFile = $null
$script:Utf8 = [System.Text.UTF8Encoding]::new($false)

function Initialize-Spike {
    param([Parameter(Mandatory)][string] $AgentExe, [Parameter(Mandatory)][string] $ArtifactsDir)
    $script:AgentExe = $AgentExe
    $script:ArtDir = $ArtifactsDir
    New-Item -ItemType Directory -Force -Path $ArtifactsDir | Out-Null
    $script:LogFile = Join-Path $ArtifactsDir 'spike.log'
    New-Item -ItemType File -Force -Path $script:LogFile | Out-Null
}

function Add-Secret {
    param([string] $Value)
    if ($Value) {
        $script:Secrets.Add($Value)
        Write-Host "::add-mask::$Value"
    }
}

function Protect-Text {
    param([string] $Text)
    if ($null -eq $Text) { return '' }
    foreach ($s in $script:Secrets) {
        if ($s) { $Text = $Text.Replace($s, '***') }
    }
    return $Text
}

function Write-Log {
    param([string] $Message)
    $line = '[{0:HH:mm:ss.fff}] {1}' -f (Get-Date), (Protect-Text $Message)
    Write-Host $line
    Add-Content -Path $script:LogFile -Value $line -Encoding utf8
}

function Save-Text {
    param([string] $Name, [string] $Text)
    $path = Join-Path $script:ArtDir $Name
    New-Item -ItemType Directory -Force -Path (Split-Path $path -Parent) | Out-Null
    [System.IO.File]::WriteAllText($path, (Protect-Text $Text), $script:Utf8)
}

# Verdict: pass | fail | info | unavailable
function Add-Result {
    param(
        [Parameter(Mandatory)][string] $Item,
        [Parameter(Mandatory)][string] $Check,
        [Parameter(Mandatory)][ValidateSet('pass', 'fail', 'info', 'unavailable')][string] $Verdict,
        [string] $Detail = ''
    )
    $row = [ordered]@{ item = $Item; check = $Check; verdict = $Verdict; detail = (Protect-Text $Detail) }
    $script:Results.Add([pscustomobject]$row)
    $json = $row | ConvertTo-Json -Compress
    Add-Content -Path (Join-Path $script:ArtDir 'results.jsonl') -Value $json -Encoding utf8
    Write-Host ("::notice title=RESULT item {0}::{1} => {2}: {3}" -f $Item, $Check, $Verdict, (Protect-Text $Detail).Split("`n")[0])
    Write-Log ("RESULT item={0} check='{1}' verdict={2} detail={3}" -f $Item, $Check, $Verdict, $Detail)
}

function New-RandomPassword {
    $bytes = [System.Security.Cryptography.RandomNumberGenerator]::GetBytes(16)
    return 'Rs!' + [Convert]::ToHexString($bytes) + 'aA1'
}

# --- agent CLI ---------------------------------------------------------------

function Invoke-Agent {
    param(
        [string] $Endpoint,
        [Parameter(Mandatory)][string[]] $AgentArgs,
        [hashtable] $Env = @{},
        [int] $TimeoutSec = 60,
        [switch] $NoEndpoint
    )
    $psi = [System.Diagnostics.ProcessStartInfo]::new($script:AgentExe)
    if ((-not $NoEndpoint) -and $Endpoint) {
        $psi.ArgumentList.Add('--endpoint'); $psi.ArgumentList.Add($Endpoint)
    }
    foreach ($a in $AgentArgs) { $psi.ArgumentList.Add($a) }
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.StandardOutputEncoding = $script:Utf8
    $psi.StandardErrorEncoding = $script:Utf8
    foreach ($k in $Env.Keys) { $psi.Environment[$k] = [string]$Env[$k] }
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $startedAt = [DateTime]::UtcNow
    $p = [System.Diagnostics.Process]::Start($psi)
    $outTask = $p.StandardOutput.ReadToEndAsync()
    $errTask = $p.StandardError.ReadToEndAsync()
    $timedOut = $false
    if (-not $p.WaitForExit($TimeoutSec * 1000)) {
        $timedOut = $true
        try { $p.Kill($true) } catch { }
    }
    $endedAt = [DateTime]::UtcNow
    $sw.Stop()
    # A daemon spawned by this CLI can inherit the pipes and keep them open; do not wait forever.
    $outDone = $outTask.Wait(3000)
    $errDone = $errTask.Wait(3000)
    $o = if ($outDone) { $outTask.Result } else { '' }
    $e = if ($errDone) { $errTask.Result } else { '' }
    $exit = if ($timedOut) { -999 } else { $p.ExitCode }
    return [pscustomobject]@{
        ExitCode   = $exit
        Out        = $o
        Err        = $e
        Ms         = $sw.ElapsedMilliseconds
        TimedOut   = $timedOut
        PipesHeld  = ((-not $outDone) -or (-not $errDone))
        StartedUtc = $startedAt
        EndedUtc   = $endedAt
        Args       = ($AgentArgs -join ' ')
    }
}

function Format-AgentResult {
    param($R)
    $t = "exit=$($R.ExitCode) ms=$($R.Ms) pipesHeld=$($R.PipesHeld) timedOut=$($R.TimedOut)"
    if ($R.Out) { $t += "`n--stdout--`n$($R.Out.TrimEnd())" }
    if ($R.Err) { $t += "`n--stderr--`n$($R.Err.TrimEnd())" }
    return $t
}

# ironrdp-agent 0.1.0 only has a foreground `daemon-start`; run it detached with output captured to files.
# A password is handed to the daemon through an overlay .rdp file (not through argv).
$script:Daemons = @{}
function Start-AgentDaemon {
    param([Parameter(Mandatory)][string] $Endpoint, [string] $User, [string] $Password, [string[]] $ExtraArgs = @(), [string] $Tag = 'd')
    $dir = Join-Path $script:ArtDir 'daemons'
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $safe = ($Endpoint -replace '[^A-Za-z0-9]', '_')
    $argList = @('--endpoint', $Endpoint, 'daemon-start')
    $overlay = $null
    if ($Password) {
        $overlay = Join-Path $script:OverlayDir "$safe.rdp"
        New-Item -ItemType Directory -Force -Path $script:OverlayDir | Out-Null
        [System.IO.File]::WriteAllText($overlay, "username:s:$User`nClearTextPassword:s:$Password`n", $script:Utf8)
        $argList += @('--overlay', $overlay)
    }
    $argList += $ExtraArgs
    $outF = Join-Path $dir "$safe.$Tag.out.txt"; $errF = Join-Path $dir "$safe.$Tag.err.txt"
    $p = Start-Process -FilePath $script:AgentExe -ArgumentList $argList -RedirectStandardOutput $outF -RedirectStandardError $errF -PassThru -WindowStyle Hidden
    $script:Daemons[$Endpoint] = [pscustomobject]@{ Pid = $p.Id; Out = $outF; Err = $errF; Overlay = $overlay; Process = $p }
    $deadline = (Get-Date).AddSeconds(30)
    do {
        Start-Sleep -Milliseconds 300
        $st = Invoke-Agent -Endpoint $Endpoint -AgentArgs @('status') -TimeoutSec 10
        if ($st.ExitCode -eq 0) { return [pscustomobject]@{ Up = $true; Pid = $p.Id; Status = $st.Out.Trim() } }
    } while ((Get-Date) -lt $deadline -and -not $p.HasExited)
    return [pscustomobject]@{ Up = $false; Pid = $p.Id; Status = "exited=$($p.HasExited) " + (Get-Content $errF -Raw -ErrorAction SilentlyContinue) }
}

function Stop-AgentDaemon {
    param([Parameter(Mandatory)][string] $Endpoint, [switch] $Quiet)
    $d = $script:Daemons[$Endpoint]
    if (-not $d) { return $false }
    try { Stop-Process -Id $d.Pid -Force -ErrorAction Stop } catch { }
    Start-Sleep -Milliseconds 500
    if ($d.Overlay -and (Test-Path $d.Overlay)) { Remove-Item $d.Overlay -Force -ErrorAction SilentlyContinue }
    foreach ($f in @($d.Out, $d.Err)) { if (Test-Path $f) { $t = [System.IO.File]::ReadAllText($f); [System.IO.File]::WriteAllText($f, (Protect-Text $t), $script:Utf8) } }
    $script:Daemons.Remove($Endpoint)
    return $true
}

function Get-AgentStatus {
    param([string] $Endpoint)
    $r = Invoke-Agent -Endpoint $Endpoint -AgentArgs @('status') -TimeoutSec 20
    $text = ($r.Out + "`n" + $r.Err)
    $state = $null
    if ($text -match '(?m)^state:\s*(\S+)') { $state = $Matches[1] }
    $frame = $null
    if ($text -match '(?m)^frame:\s*(\d+)') { $frame = [int]$Matches[1] }
    $w = $null; $h = $null
    if ($text -match '(?m)^resolution:\s*(\d+)x(\d+)') { $w = [int]$Matches[1]; $h = [int]$Matches[2] }
    return [pscustomobject]@{ Exit = $r.ExitCode; Text = $text; State = $state; Frame = $frame; Width = $w; Height = $h }
}

# Poll status until Connected / Failed / timeout. Records each distinct state text once.
function Wait-AgentConnected {
    param([string] $Endpoint, [int] $TimeoutSec = 90, [string] $Label = 'connect')
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $seen = [System.Collections.Generic.List[string]]::new()
    $last = $null
    do {
        $s = Get-AgentStatus -Endpoint $Endpoint
        $key = "$($s.Exit)|$($s.State)"
        if ($key -ne $last) {
            $seen.Add(("[{0:HH:mm:ss.fff}] {1}" -f (Get-Date), (Protect-Text $s.Text.Trim())))
            $last = $key
        }
        if ($s.State -eq 'Connected' -and $s.Width) { break }
        if ($s.State -eq 'Failed') { break }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    Save-Text "status-$Label.txt" ($seen -join "`n---`n")
    return $s
}

# --- RDP server (Windows) ---------------------------------------------------------

$script:TsPath = 'HKLM:\System\CurrentControlSet\Control\Terminal Server'
$script:RdpTcpPath = "$script:TsPath\WinStations\RDP-Tcp"

function Test-TcpPort {
    param([string] $HostName, [int] $Port)
    $c = [System.Net.Sockets.TcpClient]::new()
    try {
        $ar = $c.BeginConnect($HostName, $Port, $null, $null)
        if (-not $ar.AsyncWaitHandle.WaitOne(1000)) { return $false }
        $c.EndConnect($ar); return $true
    } catch { return $false } finally { $c.Dispose() }
}

function Wait-TcpPort {
    param([string] $HostName, [int] $Port, [int] $TimeoutSec = 30)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    do {
        if (Test-TcpPort -HostName $HostName -Port $Port) { return $true }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    return $false
}

function Enable-LocalRdp {
    param([ValidateSet(0, 1)][int] $Nla)
    Set-ItemProperty -Path $script:TsPath -Name 'fDenyTSConnections' -Value 0
    Set-ItemProperty -Path $script:RdpTcpPath -Name 'UserAuthentication' -Value $Nla
    Set-Service -Name TermService -StartupType Automatic
    Start-Service -Name TermService -ErrorAction SilentlyContinue
    Restart-Service -Name TermService -Force
    New-NetFirewallRule -Name 'RdpSpikeLoopback' -DisplayName 'RDP spike loopback' -Direction Inbound -Action Allow `
        -Protocol TCP -LocalPort 3389 -RemoteAddress '127.0.0.1' -ErrorAction SilentlyContinue | Out-Null
    $up = Wait-TcpPort -HostName '127.0.0.1' -Port 3389 -TimeoutSec 60
    $ua = (Get-ItemProperty -Path $script:RdpTcpPath -Name 'UserAuthentication').UserAuthentication
    $sl = (Get-ItemProperty -Path $script:RdpTcpPath -Name 'SecurityLayer' -ErrorAction SilentlyContinue)
    $slv = if ($sl) { $sl.SecurityLayer } else { '(unset)' }
    $wmi = ''
    try {
        $g = Get-CimInstance -Namespace 'root\cimv2\TerminalServices' -ClassName Win32_TSGeneralSetting -Filter "TerminalName='RDP-Tcp'"
        $wmi = "WMI UserAuthenticationRequired=$($g.UserAuthenticationRequired) SecurityLayer=$($g.SecurityLayer) MinEncryptionLevel=$($g.MinEncryptionLevel)"
    } catch { $wmi = "WMI query failed: $($_.Exception.Message)" }
    return [pscustomobject]@{ Listening = $up; UserAuthentication = $ua; SecurityLayerReg = $slv; Wmi = $wmi }
}

function New-SpikeUser {
    param([Parameter(Mandatory)][string] $Name)
    $pw = New-RandomPassword
    Add-Secret $pw
    $sec = ConvertTo-SecureString -String $pw -AsPlainText -Force
    New-LocalUser -Name $Name -Password $sec -PasswordNeverExpires -AccountNeverExpires -UserMayNotChangePassword | Out-Null
    Add-LocalGroupMember -Group 'Remote Desktop Users' -Member $Name
    return $pw
}

function Get-RdpSessions {
    $lines = (& qwinsta.exe 2>&1) | ForEach-Object { "$_" }
    $out = @()
    foreach ($l in $lines) {
        if ($l -match '(rdpspike\d)\s+(\d+)\s+(\S+)') {
            $out += [pscustomobject]@{ User = $Matches[1]; Id = [int]$Matches[2]; State = $Matches[3] }
        }
    }
    return $out
}

function Stop-SpikeSessions {
    param([string] $OnlyUser)
    $all = (& qwinsta.exe 2>&1) -join "`n"
    Write-Log "qwinsta:`n$all"
    foreach ($s in (Get-RdpSessions)) {
        if ((-not $OnlyUser) -or $s.User -eq $OnlyUser) {
            Write-Log "logoff session $($s.Id) ($($s.User), $($s.State))"
            & logoff.exe $s.Id 2>&1 | ForEach-Object { Write-Log "logoff: $_" }
        }
    }
    Start-Sleep -Seconds 3
}

function Install-Receiver {
    param([string] $SourceDir)
    $root = 'C:\rdp-spike'
    New-Item -ItemType Directory -Force -Path "$root\recv" | Out-Null
    Copy-Item -Path (Join-Path $SourceDir 'receiver.ps1') -Destination "$root\receiver.ps1" -Force
    & icacls.exe $root /grant 'Users:(OI)(CI)M' /T /C | Out-Null
    $runKey = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Run'
    $cmd = 'powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File C:\rdp-spike\receiver.ps1'
    Set-ItemProperty -Path $runKey -Name 'RdpSpikeReceiver' -Value $cmd
}

# --- receiver (target-side observation) ---------------------------------------------------

function Get-ReceiverDir { param([string] $User) return "C:\rdp-spike\recv\$User" }

function Read-ReceiverJson {
    param([string] $User, [string] $Name)
    $p = Join-Path (Get-ReceiverDir $User) $Name
    for ($i = 0; $i -lt 5; $i++) {
        try {
            if (Test-Path $p) {
                $raw = [System.IO.File]::ReadAllText($p, $script:Utf8)
                if ($raw) { return ($raw | ConvertFrom-Json) }
            }
        } catch { Start-Sleep -Milliseconds 50 }
    }
    return $null
}

function Wait-ReceiverReady {
    param([string] $User, [int] $TimeoutSec = 150)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    do {
        $r = Read-ReceiverJson -User $User -Name 'ready.json'
        if ($r) { return $r }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    return $null
}

function Get-ReceiverBytes {
    param([string] $User)
    $p = Join-Path (Get-ReceiverDir $User) 'text.bin'
    for ($i = 0; $i -lt 5; $i++) {
        try {
            if (Test-Path $p) { return [System.IO.File]::ReadAllBytes($p) }
            return [byte[]]@()
        } catch { Start-Sleep -Milliseconds 50 }
    }
    return [byte[]]@()
}

function Reset-Receiver {
    param([string] $User)
    $dir = Get-ReceiverDir $User
    $before = (Read-ReceiverJson -User $User -Name 'reset.ack')
    $n = if ($before) { [int]$before.n } else { 0 }
    [System.IO.File]::WriteAllText("$dir\reset.flag", 'x')
    $deadline = (Get-Date).AddSeconds(10)
    do {
        Start-Sleep -Milliseconds 100
        $a = Read-ReceiverJson -User $User -Name 'reset.ack'
        if ($a -and [int]$a.n -gt $n) { return $true }
    } while ((Get-Date) -lt $deadline)
    return $false
}

# Wait until text.bin has not changed for $QuietMs, at most $MaxSec. Returns bytes and elapsed ms.
function Wait-ReceiverStable {
    param([string] $User, [int] $QuietMs = 2500, [int] $MaxSec = 40, [byte[]] $Expected)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $last = Get-ReceiverBytes -User $User
    $lastChange = $sw.ElapsedMilliseconds
    while ($sw.ElapsedMilliseconds -lt ($MaxSec * 1000)) {
        Start-Sleep -Milliseconds 100
        $cur = Get-ReceiverBytes -User $User
        $same = ($cur.Length -eq $last.Length)
        if ($same) {
            for ($i = 0; $i -lt $cur.Length; $i++) { if ($cur[$i] -ne $last[$i]) { $same = $false; break } }
        }
        if (-not $same) { $last = $cur; $lastChange = $sw.ElapsedMilliseconds }
        elseif (($sw.ElapsedMilliseconds - $lastChange) -ge $QuietMs) { break }
        if ($Expected -and $same -and $cur.Length -eq $Expected.Length) {
            $eq = $true
            for ($i = 0; $i -lt $cur.Length; $i++) { if ($cur[$i] -ne $Expected[$i]) { $eq = $false; break } }
            if ($eq -and ($sw.ElapsedMilliseconds - $lastChange) -ge 800) { break }
        }
    }
    return [pscustomobject]@{ Bytes = $last; SettleMs = $lastChange; TotalMs = $sw.ElapsedMilliseconds }
}

function Compare-Text {
    param([string] $Expected, [byte[]] $ActualBytes)
    $actual = $script:Utf8.GetString($ActualBytes)
    $expBytes = $script:Utf8.GetBytes($Expected)
    $sha = [System.Security.Cryptography.SHA256]
    $eqBytes = ($expBytes.Length -eq $ActualBytes.Length)
    if ($eqBytes) { for ($i = 0; $i -lt $expBytes.Length; $i++) { if ($expBytes[$i] -ne $ActualBytes[$i]) { $eqBytes = $false; break } } }
    $detail = "expectedBytes=$($expBytes.Length) actualBytes=$($ActualBytes.Length) expectedSha256=$([Convert]::ToHexString($sha::HashData($expBytes)).ToLower()) actualSha256=$([Convert]::ToHexString($sha::HashData($ActualBytes)).ToLower())"
    if (-not $eqBytes) {
        $n = [Math]::Min($Expected.Length, $actual.Length)
        $idx = -1
        for ($i = 0; $i -lt $n; $i++) { if ($Expected[$i] -ne $actual[$i]) { $idx = $i; break } }
        if ($idx -lt 0 -and $Expected.Length -ne $actual.Length) { $idx = $n }
        $kind = 'content-differs'
        $sortE = -join ($Expected.ToCharArray() | Sort-Object)
        $sortA = -join ($actual.ToCharArray() | Sort-Object)
        if ($sortE -eq $sortA) { $kind = 'REORDERED (same multiset of UTF-16 units)' }
        elseif ($actual.Length -lt $Expected.Length) { $kind = 'MISSING-or-changed (actual shorter)' }
        elseif ($actual.Length -gt $Expected.Length) { $kind = 'EXTRA-or-changed (actual longer)' }
        $from = [Math]::Max(0, $idx - 10)
        $detail += " firstDiffIndex=$idx kind=$kind expectedAround='$($Expected.Substring($from, [Math]::Min(40, $Expected.Length - $from)))' actualAround='$($actual.Substring($from, [Math]::Min(40, [Math]::Max(0, $actual.Length - $from))))'"
    }
    return [pscustomobject]@{ Equal = $eqBytes; Detail = $detail; Actual = $actual }
}

# Split a string into chunks of at most $Max Unicode scalar values (never splits a surrogate pair).
function Split-Scalars {
    param([string] $Text, [int] $Max = 96)
    $chunks = [System.Collections.Generic.List[string]]::new()
    $sb = [System.Text.StringBuilder]::new()
    $count = 0
    for ($i = 0; $i -lt $Text.Length; $i++) {
        $c = $Text[$i]
        [void]$sb.Append($c)
        if ([char]::IsHighSurrogate($c) -and ($i + 1) -lt $Text.Length) { $i++; [void]$sb.Append($Text[$i]) }
        $count++
        if ($count -ge $Max) { $chunks.Add($sb.ToString()); [void]$sb.Clear(); $count = 0 }
    }
    if ($sb.Length -gt 0) { $chunks.Add($sb.ToString()) }
    return , $chunks
}

# --- images ---------------------------------------------------------------------------

function Initialize-ImageTools {
    if ('RdpSpikeImage' -as [type]) { return }
    Add-Type -AssemblyName System.Drawing
    $refs = @([System.Drawing.Bitmap].Assembly.Location, [System.Drawing.Rectangle].Assembly.Location, [System.Drawing.Imaging.ImageLockMode].Assembly.Location) | Select-Object -Unique
    Add-Type -ReferencedAssemblies $refs -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
public static class RdpSpikeImage {
    public static string Analyze(string path) {
        using (var bmp = new Bitmap(path)) {
            var rect = new Rectangle(0, 0, bmp.Width, bmp.Height);
            var d = bmp.LockBits(rect, ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
            int bytes = Math.Abs(d.Stride) * bmp.Height;
            var buf = new byte[bytes];
            System.Runtime.InteropServices.Marshal.Copy(d.Scan0, buf, 0, bytes);
            bmp.UnlockBits(d);
            var counts = new Dictionary<int, int>();
            long total = (long)bmp.Width * bmp.Height;
            for (int y = 0; y < bmp.Height; y++) {
                int row = y * Math.Abs(d.Stride);
                for (int x = 0; x < bmp.Width; x++) {
                    int o = row + x * 4;
                    int key = (buf[o + 2] << 16) | (buf[o + 1] << 8) | buf[o];
                    int c; counts.TryGetValue(key, out c); counts[key] = c + 1;
                }
            }
            int max = 0; foreach (var kv in counts) if (kv.Value > max) max = kv.Value;
            return bmp.Width + "x" + bmp.Height + " distinctColors=" + counts.Count + " dominantColorShare=" + ((double)max / total).ToString("0.0000");
        }
    }
    public static long DiffPixels(string a, string b) {
        using (var ba = new Bitmap(a)) using (var bb = new Bitmap(b)) {
            if (ba.Width != bb.Width || ba.Height != bb.Height) return -1;
            long diff = 0;
            for (int y = 0; y < ba.Height; y += 2) for (int x = 0; x < ba.Width; x += 2)
                if (ba.GetPixel(x, y).ToArgb() != bb.GetPixel(x, y).ToArgb()) diff++;
            return diff;
        }
    }
}
'@
}

function Test-Png {
    param([string] $Path)
    $info = [ordered]@{ path = (Split-Path $Path -Leaf); exists = (Test-Path $Path) }
    if (-not $info.exists) { return [pscustomobject]@{ Valid = $false; Text = 'file missing'; Info = $info } }
    $b = [System.IO.File]::ReadAllBytes($Path)
    $sig = ($b.Length -ge 8) -and ($b[0] -eq 0x89) -and ($b[1] -eq 0x50) -and ($b[2] -eq 0x4E) -and ($b[3] -eq 0x47) -and ($b[4] -eq 0x0D) -and ($b[5] -eq 0x0A) -and ($b[6] -eq 0x1A) -and ($b[7] -eq 0x0A)
    $info.bytes = $b.Length
    $info.signature = $sig
    if (-not $sig) { return [pscustomobject]@{ Valid = $false; Text = "bad PNG signature, bytes=$($b.Length)"; Info = $info } }
    $w = ([int]$b[16] -shl 24) + ([int]$b[17] -shl 16) + ([int]$b[18] -shl 8) + [int]$b[19]
    $h = ([int]$b[20] -shl 24) + ([int]$b[21] -shl 16) + ([int]$b[22] -shl 8) + [int]$b[23]
    try {
        Initialize-ImageTools
        $an = [RdpSpikeImage]::Analyze($Path)
    } catch { $an = "decode failed: $($_.Exception.Message)" }
    $valid = ($w -gt 0 -and $h -gt 0 -and $an -match 'distinctColors=(\d+)' -and [int]$Matches[1] -gt 8)
    return [pscustomobject]@{ Valid = $valid; Text = "bytes=$($b.Length) IHDR=${w}x${h} $an"; Info = $info; Width = $w; Height = $h }
}

# --- misc ---------------------------------------------------------------------------------

function Get-AgentProcessCommandLines {
    $rows = Get-CimInstance Win32_Process -Filter "Name='ironrdp-agent.exe'" -ErrorAction SilentlyContinue
    return @($rows | ForEach-Object { [pscustomobject]@{ Pid = $_.ProcessId; ParentPid = $_.ParentProcessId; CommandLine = $_.CommandLine } })
}

function Get-PipeNames {
    try { return @([System.IO.Directory]::GetFiles('\\.\pipe\') | ForEach-Object { $_.Replace('\\.\pipe\', '') }) } catch { return @() }
}

function Start-TcpRelay {
    param([int] $ListenPort, [string] $TargetHost, [int] $TargetPort)
    if (-not ('RdpSpikeRelay' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Net;
using System.Net.Sockets;
using System.Threading;
public class RdpSpikeRelay {
    TcpListener l; volatile bool stop; public int Accepted;
    public void Start(int port, string host, int tport) {
        l = new TcpListener(IPAddress.Loopback, port); l.Start();
        var t = new Thread(() => {
            while (!stop) {
                TcpClient c;
                try { c = l.AcceptTcpClient(); } catch { break; }
                Interlocked.Increment(ref Accepted);
                var cc = c;
                new Thread(() => {
                    try {
                        var u = new TcpClient(host, tport);
                        var a = cc.GetStream(); var b = u.GetStream();
                        var t1 = new Thread(() => { try { a.CopyTo(b); } catch { } try { u.Close(); } catch { } });
                        t1.IsBackground = true; t1.Start();
                        try { b.CopyTo(a); } catch { }
                        try { cc.Close(); } catch { }
                    } catch { try { cc.Close(); } catch { } }
                }) { IsBackground = true }.Start();
            }
        });
        t.IsBackground = true; t.Start();
    }
    public void Stop() { stop = true; try { l.Stop(); } catch { } }
}
'@
    }
    $r = [RdpSpikeRelay]::new()
    $r.Start($ListenPort, $TargetHost, $TargetPort)
    return $r
}
