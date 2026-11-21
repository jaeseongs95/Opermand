# Target-side receiver. Runs inside the RDP session (started by the HKLM Run key at logon).
# It records what the target actually received so the harness can judge input from the target side,
# not from screen captures. Windows PowerShell 5.1 compatible.
$ErrorActionPreference = 'Stop'
$user = $env:USERNAME
$dir = "C:\rdp-spike\recv\$user"
New-Item -ItemType Directory -Force -Path $dir | Out-Null
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class RxW32 {
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    [DllImport("user32.dll")] public static extern IntPtr SendMessage(IntPtr h, int m, IntPtr w, IntPtr l);
    [DllImport("kernel32.dll")] public static extern bool ProcessIdToSessionId(int pid, out int sid);
}
'@
[void][RxW32]::SetProcessDPIAware()
[System.Windows.Forms.Application]::EnableVisualStyles()
$utf8 = New-Object System.Text.UTF8Encoding($false)

$global:RxBuf = New-Object System.Text.StringBuilder
$global:RxLastText = $null
$global:RxLastState = ''
$global:RxResetN = 0

function Rx-Log([string] $s) {
    $null = $global:RxBuf.AppendLine(([DateTime]::UtcNow.ToString('o')) + '|' + $s)
}

$screen = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
$form = New-Object System.Windows.Forms.Form
$form.FormBorderStyle = 'None'
$form.StartPosition = 'Manual'
$form.Location = New-Object System.Drawing.Point(0, 0)
$form.Size = New-Object System.Drawing.Size($screen.Width, $screen.Height)
$form.TopMost = $true
$form.BackColor = [System.Drawing.Color]::FromArgb(70, 110, 160)

$half = [int]($screen.Width / 2)
$tb = New-Object System.Windows.Forms.TextBox
$tb.Name = 'input'; $tb.Multiline = $true; $tb.AcceptsReturn = $true; $tb.WordWrap = $true
$tb.ScrollBars = 'Vertical'; $tb.MaxLength = 2000000
$tb.Font = New-Object System.Drawing.Font('Consolas', 11)
$tb.Location = New-Object System.Drawing.Point(20, 20)
$tb.Size = New-Object System.Drawing.Size(($half - 40), 380)
$form.Controls.Add($tb)

$btn = New-Object System.Windows.Forms.Button
$btn.Name = 'btnA'; $btn.Text = 'CLICK-A'
$btn.Location = New-Object System.Drawing.Point(20, 430)
$btn.Size = New-Object System.Drawing.Size(200, 80)
$form.Controls.Add($btn)

$pnl = New-Object System.Windows.Forms.Panel
$pnl.Name = 'panel'; $pnl.BackColor = [System.Drawing.Color]::White
$pnl.Location = New-Object System.Drawing.Point(260, 430)
$pnl.Size = New-Object System.Drawing.Size(300, 200)
$form.Controls.Add($pnl)

$sb = New-Object System.Windows.Forms.TextBox
$sb.Name = 'scroll'; $sb.Multiline = $true; $sb.ReadOnly = $true; $sb.ScrollBars = 'Vertical'
$sb.Font = New-Object System.Drawing.Font('Consolas', 11)
$sb.Location = New-Object System.Drawing.Point(($half + 20), 20)
$sb.Size = New-Object System.Drawing.Size(($half - 40), 500)
$sb.Text = ((1..400 | ForEach-Object { 'line {0:000}' -f $_ }) -join "`r`n")
$form.Controls.Add($sb)

$tb.Add_KeyDown({ param($s, $e) Rx-Log ('KeyDown|' + $e.KeyCode + '|ctrl=' + $e.Control + '|shift=' + $e.Shift) })
$tb.Add_KeyPress({ param($s, $e) Rx-Log ('KeyPress|U+{0:X4}' -f [int]$e.KeyChar) })
$btn.Add_Click({ Rx-Log 'Click|btnA' })
foreach ($c in @($btn, $pnl, $form)) {
    $c.Add_MouseDown({ param($s, $e) Rx-Log ('MouseDown|{0}|{1}|{2},{3}|screen={4},{5}' -f $s.Name, $e.Button, $e.X, $e.Y, [System.Windows.Forms.Cursor]::Position.X, [System.Windows.Forms.Cursor]::Position.Y) })
    $c.Add_MouseUp({ param($s, $e) Rx-Log ('MouseUp|{0}|{1}|{2},{3}' -f $s.Name, $e.Button, $e.X, $e.Y) })
}
foreach ($c in @($sb, $tb, $form, $pnl)) {
    $c.Add_MouseWheel({ param($s, $e) Rx-Log ('Wheel|{0}|delta={1}' -f $s.Name, $e.Delta) })
}

function Center-Of($ctl) {
    $p = $ctl.PointToScreen((New-Object System.Drawing.Point([int]($ctl.Width / 2), [int]($ctl.Height / 2))))
    return @{ x = $p.X; y = $p.Y }
}

$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 50
$timer.Add_Tick({
    try {
        if ($global:RxBuf.Length -gt 0) {
            [System.IO.File]::AppendAllText("$dir\events.log", $global:RxBuf.ToString(), $utf8)
            [void]$global:RxBuf.Clear()
        }
        if (Test-Path "$dir\reset.flag") {
            $tb.Text = ''
            Remove-Item "$dir\reset.flag" -Force
            $global:RxResetN++
            [System.IO.File]::WriteAllText("$dir\reset.ack", ('{"n":' + $global:RxResetN + '}'), $utf8)
            $tb.Focus() | Out-Null
        }
        $t = $tb.Text
        if ($t -ne $global:RxLastText) {
            [System.IO.File]::WriteAllBytes("$dir\text.tmp", $utf8.GetBytes($t))
            Move-Item -Force "$dir\text.tmp" "$dir\text.bin"
            $global:RxLastText = $t
        }
        $pos = [System.Windows.Forms.Cursor]::Position
        $first = [int][RxW32]::SendMessage($sb.Handle, 0xCE, [IntPtr]::Zero, [IntPtr]::Zero)
        $focused = ''
        if ($form.ActiveControl) { $focused = $form.ActiveControl.Name }
        $state = '{"cursor":{"x":' + $pos.X + ',"y":' + $pos.Y + '},"firstVisibleLine":' + $first + ',"focused":"' + $focused + '","tbLength":' + $t.Length + '}'
        if ($state -ne $global:RxLastState) {
            [System.IO.File]::WriteAllText("$dir\state.json", $state, $utf8)
            $global:RxLastState = $state
        }
    } catch { [System.IO.File]::AppendAllText("$dir\receiver-error.log", ($_ | Out-String), $utf8) }
})

$form.Add_Shown({
    $form.Activate()
    $tb.Focus() | Out-Null
    $sid = 0
    [void][RxW32]::ProcessIdToSessionId($PID, [ref]$sid)
    $layout = @{
        user = $user; pid = $PID; session = $sid
        screen = @{ w = $screen.Width; h = $screen.Height }
        input = (Center-Of $tb); button = (Center-Of $btn); panel = (Center-Of $pnl); scroll = (Center-Of $sb)
        startedUtc = [DateTime]::UtcNow.ToString('o')
    }
    [System.IO.File]::WriteAllText("$dir\ready.json", ($layout | ConvertTo-Json -Depth 5 -Compress), $utf8)
    $timer.Start()
})

[void][System.Windows.Forms.Application]::Run($form)
