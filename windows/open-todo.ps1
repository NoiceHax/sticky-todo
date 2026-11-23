# Opens the sticky to-do note at login and keeps it in front of Brave.
# Run by the "StickyTodo" scheduled task (at logon). Safe to run by hand too.
# HTTPS (Tailscale serve) first; plain Tailscale HTTP as a fallback.
$Urls    = @('https://homelab.tail3ed9c7.ts.net:3103/', 'http://100.120.194.10:3102/')
# A separate Brave profile = a separate Brave instance, so it never mixes with your main
# Brave window or its session restore.
$NoteProfile = Join-Path $env:LOCALAPPDATA 'StickyTodo\brave-profile'
$Browser = @("$env:LOCALAPPDATA\BraveSoftware\Brave-Browser\Application\brave.exe",
             "$env:ProgramFiles\BraveSoftware\Brave-Browser\Application\brave.exe") |
           Where-Object { Test-Path $_ } | Select-Object -First 1

Add-Type @'
using System; using System.Text; using System.Runtime.InteropServices;
public static class Win {
  public delegate bool EnumProc(IntPtr h, IntPtr p);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc f, IntPtr p);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint f);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
}
'@

function Find-Windows([scriptblock]$match) {
  $found = New-Object System.Collections.ArrayList
  [Win]::EnumWindows({ param($h, $p)
    if ([Win]::IsWindowVisible($h)) {
      $sb = New-Object System.Text.StringBuilder 256
      [void][Win]::GetWindowText($h, $sb, 256)
      $procId = 0; [void][Win]::GetWindowThreadProcessId($h, [ref]$procId)
      $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
      if ($proc -and (& $match $proc.ProcessName $sb.ToString())) { [void]$found.Add($h) }
    }
    $true }, [IntPtr]::Zero) | Out-Null
  return $found
}
function Find-Note  { Find-Windows { param($n, $t) $n -eq 'brave' -and $t -match '^To do' } }
function Find-Brave { Find-Windows { param($n, $t) $n -eq 'brave' -and $t -ne '' -and $t -notmatch '^To do' } }

function Raise($h) {
  # TOPMOST then NOTOPMOST lifts it above everything without pinning it there forever.
  $flags = 0x0001 -bor 0x0002 -bor 0x0040   # NOSIZE | NOMOVE | SHOWWINDOW
  [void][Win]::ShowWindow($h, 9)             # restore if minimized
  [void][Win]::SetWindowPos($h, [IntPtr](-1), 0, 0, 0, 0, $flags)
  [void][Win]::SetWindowPos($h, [IntPtr](-2), 0, 0, 0, 0, $flags)
  [void][Win]::SetForegroundWindow($h)
}

# Wait (up to 45s) for the network/Tailscale so the page loads instead of an error page.
$Url = $null
for ($i = 0; $i -lt 45 -and -not $Url; $i++) {
  foreach ($u in $Urls) {
    try { if ((Invoke-WebRequest "${u}healthz" -UseBasicParsing -TimeoutSec 2).StatusCode -eq 200) { $Url = $u; break } } catch {}
  }
  if (-not $Url) { Start-Sleep -Seconds 1 }
}
if (-not $Url) { $Url = $Urls[0] }

# Open the note unless it's already open. Top-right corner.
$note = Find-Note | Select-Object -First 1
if (-not $note) {
  Add-Type -AssemblyName System.Windows.Forms
  $wa = [System.Windows.Forms.Screen]::PrimaryScreen.WorkingArea
  # Tall enough for the Deadlines and Tasks lists to each show a few items, but never
  # taller than the screen allows.
  $w = 380; $h = [Math]::Min(720, $wa.Height - 32)
  Start-Process $Browser -ArgumentList @(
    "--app=$Url", "--user-data-dir=`"$NoteProfile`"", '--no-first-run', '--no-default-browser-check',
    "--window-size=$w,$h", "--window-position=$($wa.Right - $w - 16),$($wa.Top + 16)")
  for ($i = 0; $i -lt 30 -and -not $note; $i++) { Start-Sleep -Milliseconds 500; $note = Find-Note | Select-Object -First 1 }
}
if (-not $note) { exit 1 }
Raise $note

# Windows reopens Brave after sign-in ("restart apps"). Watch for 2 minutes; whenever a new
# Brave window shows up, put the note back in front of it.
$seen = @(Find-Brave)
for ($i = 0; $i -lt 120; $i++) {
  Start-Sleep -Seconds 1
  $now = @(Find-Brave)
  if (@($now | Where-Object { $seen -notcontains $_ }).Count -gt 0) {
    Start-Sleep -Milliseconds 800   # let Brave finish painting/activating first
    $note = Find-Note | Select-Object -First 1
    if ($note) { Raise $note }
  }
  $seen = $now
}
