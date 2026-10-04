# The canary stack as native Windows processes, for a machine where Docker is unavailable: Prometheus (Windows
# binary), the splitter, the load generator and the CanaryJudge server, all bound to 127.0.0.1. Target instances
# are started by the bench runners with --launcher process. Usage:
#   pwsh -File scripts/native_stack.ps1 up    (start; PIDs in build/run/stack.pids)
#   pwsh -File scripts/native_stack.ps1 down  (stop everything it started, and any target JVMs)
param([string]$Action = "up", [string]$Trace = "", [int]$TraceOffset = 0)
$root = Resolve-Path "$PSScriptRoot/.."
$java = "$root/build/jdk21/bin/java.exe"
$run = "$root/build/run"
New-Item -ItemType Directory -Force $run | Out-Null
$pidFile = "$run/stack.pids"

function Start-Bg($name, $exe, $argList, $envs) {
    foreach ($k in $envs.Keys) { [Environment]::SetEnvironmentVariable($k, $envs[$k], "Process") }
    $p = Start-Process -FilePath $exe -ArgumentList $argList -WorkingDirectory $root -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput "$run/$name.out.log" -RedirectStandardError "$run/$name.err.log"
    foreach ($k in $envs.Keys) { [Environment]::SetEnvironmentVariable($k, $null, "Process") }
    Add-Content $pidFile "$($p.Id) $name"
    "$name pid $($p.Id)"
}

if ($Action -eq "down") {
    if (Test-Path $pidFile) {
        Get-Content $pidFile | ForEach-Object { $id = ($_ -split " ")[0]; Stop-Process -Id $id -Force -ErrorAction SilentlyContinue }
        Remove-Item $pidFile
    }
    # target JVMs started by the runners
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like "*target-service.jar*" } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    "stopped"
    exit 0
}

$routes = "r1.primary=http://127.0.0.1:28101,r1.baseline=http://127.0.0.1:28102,r1.canary=http://127.0.0.1:28103," +
          "l1.baseline=http://127.0.0.1:28111,l1.canary=http://127.0.0.1:28112,l2.baseline=http://127.0.0.1:28121,l2.canary=http://127.0.0.1:28122"
Start-Bg "prometheus" "$root/build/prometheus/prometheus.exe" @("--config.file=deploy/windows/prometheus.yml",
    "--storage.tsdb.path=build/prom-data", "--web.listen-address=127.0.0.1:29090", "--storage.tsdb.retention.time=30d") @{}
Start-Bg "splitter" $java @("-Xmx256m", "-XX:+UseSerialGC", "-jar", "traffic/build/libs/traffic.jar", "splitter", "--port", "28000",
    "--bind", "127.0.0.1", "--lanes", "l1:0.5:0.5:request,l2:0.5:0.5:request,r1:0:0", "--routes", $routes) @{}
$lg = @("-Xmx256m", "-XX:+UseSerialGC", "-jar", "traffic/build/libs/traffic.jar", "loadgen", "--target", "http://127.0.0.1:28000",
    "--control-port", "28001", "--bind", "127.0.0.1", "--users", "50000", "--seed", "20261004")
if ($Trace -ne "") { $lg += @("--trace", $Trace, "--trace-step-s", "10", "--trace-offset", "$TraceOffset") }
Start-Bg "loadgen" $java $lg @{}
Start-Bg "server" $java @("-Xmx256m", "-XX:+UseSerialGC", "-jar", "server/build/libs/canaryjudge-server.jar") @{
    SERVER_PORT = "28090"; SERVER_ADDRESS = "127.0.0.1"; PROMETHEUS_URL = "http://127.0.0.1:29090";
    SPLITTER_URL = "http://127.0.0.1:28000"; CONFIG_DIR = "configs" }
