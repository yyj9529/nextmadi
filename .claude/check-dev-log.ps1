$today = Get-Date -Format 'yyyyMMdd'
$logDir = "$env:USERPROFILE\Desktop\dev-logs"
$found = Get-ChildItem $logDir -Filter ($today + '*.md') -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $found) {
    Write-Output '[dev-logs] No session log for today -- please write one.'
}
