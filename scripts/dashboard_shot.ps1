# Starts Grafana (Windows build, in build/grafana) against the native Prometheus, and screenshots the CanaryJudge
# dashboard for one recorded rollout with headless Chrome:
#   pwsh -File scripts/dashboard_shot.ps1 -Run ro015 -From <epoch ms> -To <epoch ms> -Out docs/dashboard-rollback.png
param([string]$Run, [long]$From, [long]$To, [string]$Out, [string]$Lane = "r1")
$root = Resolve-Path "$PSScriptRoot/.."
$env:GF_PATHS_PROVISIONING = "$root/build/grafana-prov"
$env:GF_PATHS_DATA = "$root/build/grafana-data"
$env:GF_PATHS_LOGS = "$root/build/grafana-data/log"
$env:GF_SERVER_HTTP_ADDR = "127.0.0.1"
$env:GF_SERVER_HTTP_PORT = "13000"
$env:GF_AUTH_ANONYMOUS_ENABLED = "true"
$env:GF_AUTH_ANONYMOUS_ORG_ROLE = "Viewer"
$env:GF_ANALYTICS_REPORTING_ENABLED = "false"
$g = Start-Process -FilePath "$root/build/grafana/bin/grafana.exe" -ArgumentList "server", "--homepath", "$root/build/grafana" `
    -WorkingDirectory "$root/build/grafana" -WindowStyle Hidden -PassThru
for ($i = 0; $i -lt 60; $i++) { try { Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 http://127.0.0.1:13000/api/health | Out-Null; break } catch { Start-Sleep 2 } }
$url = "http://127.0.0.1:13000/d/canaryjudge/canaryjudge-rollout?orgId=1&from=$From&to=$To&var-lane=$Lane&var-canary=$Run-canary&kiosk&theme=light"
$chrome = "C:\Program Files\Google\Chrome\Application\chrome.exe"
$profile = "$root/build/chrome-shot"
& $chrome --headless=new --disable-gpu --hide-scrollbars --user-data-dir="$profile" --window-size=1600,1080 `
    --virtual-time-budget=20000 --screenshot="$((Resolve-Path .).Path)/$Out" $url 2>&1 | Out-Null
Start-Sleep 3
Stop-Process -Id $g.Id -Force
Get-Item $Out | Select-Object Name, Length
