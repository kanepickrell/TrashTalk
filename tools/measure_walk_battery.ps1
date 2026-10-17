# Measures what walk mode costs in battery.
#
# The budget is about 8% for a one-hour screen-off recording. That number is
# only meaningful from a real device: an emulator has no battery, and a phone
# on a charger reports drain of zero.
#
#   pwsh tools/measure_walk_battery.ps1 -Minutes 60
#
# Start a walk in the app first, then unplug, then run this. It takes the
# screen off, waits, and reports the drain attributable to the app.

param(
    [int]$Minutes = 60,
    [string]$Serial = $env:ANDROID_SERIAL,
    [string]$Package = "com.trashtalk.debug"
)

$ErrorActionPreference = "Stop"
$adbArgs = if ($Serial) { @("-s", $Serial) } else { @() }

function Adb { & adb @adbArgs @args }

# A charging phone cannot report drain, and "unplug it" is not something a
# script can do for you.
$plugged = (Adb shell dumpsys battery | Select-String "powered: true")
if ($plugged) {
    throw "The device is on power. Unplug it, then run this again."
}

$recording = Adb shell dumpsys activity services $Package |
    Select-String "CleanupRecordingService"
if (-not $recording) {
    throw "No recording service running. Start a walk in the app first."
}

# Reset the accounting so the window measured is this one and not the day's.
Adb shell dumpsys batterystats --reset | Out-Null
$startLevel = [int](Adb shell dumpsys battery | Select-String "level: (\d+)").Matches.Groups[1].Value
$startedAt = Get-Date

Write-Host "Recording. Screen off for $Minutes minutes from $($startedAt.ToString('HH:mm'))." -ForegroundColor Cyan
Write-Host "Battery at $startLevel%."
Adb shell input keyevent KEYCODE_SLEEP | Out-Null

Start-Sleep -Seconds ($Minutes * 60)

$endLevel = [int](Adb shell dumpsys battery | Select-String "level: (\d+)").Matches.Groups[1].Value
$elapsed = (Get-Date) - $startedAt
$drop = $startLevel - $endLevel
$perHour = if ($elapsed.TotalHours -gt 0) { $drop / $elapsed.TotalHours } else { 0 }

Write-Host ""
Write-Host ("elapsed      {0:N0} min" -f $elapsed.TotalMinutes)
Write-Host ("battery      {0}% -> {1}%  ({2} points)" -f $startLevel, $endLevel, $drop)
Write-Host ("rate         {0:N1}% per hour" -f $perHour)

# Whole-device drain, not the app's alone: the screen is off and little else
# should be awake, but a push notification or a background sync lands in this
# number too. The per-uid figure below is the app's own share.
Write-Host ""
Write-Host "App's own share (batterystats):" -ForegroundColor Cyan
Adb shell dumpsys batterystats $Package | Select-String -Pattern "Uid|Wake lock|Sensor|mAh" |
    Select-Object -First 12

if ($perHour -gt 8) {
    Write-Host ""
    Write-Host ("OVER BUDGET: {0:N1}%/h against a budget of 8%/h." -f $perHour) -ForegroundColor Red
    Write-Host "Tune the GPS interval and the distance gate in CleanupRecordingService."
    exit 1
}

Write-Host ""
Write-Host ("Within budget: {0:N1}%/h against 8%/h." -f $perHour) -ForegroundColor Green
