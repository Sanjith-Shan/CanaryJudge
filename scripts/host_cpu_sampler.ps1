# Samples Windows host CPU every 15 s on the Windows side. Writes the latest value to -Out (for jobs that read it)
# and appends every sample to -History (epoch seconds, percent), which is joined to results rows by time.
# pwsh -File scripts/host_cpu_sampler.ps1 [-Out .work/hostcpu.txt] [-History results/host_cpu.csv] [-Minutes 600]
param([string]$Out = "$PSScriptRoot/../.work/hostcpu.txt",
      [string]$History = "$PSScriptRoot/../results/host_cpu.csv",
      [int]$Minutes = 600)
$end = (Get-Date).AddMinutes($Minutes)
if (-not (Test-Path $History)) { Set-Content -Path $History -Value "epoch_s,windows_host_cpu_pct" }
while ((Get-Date) -lt $end) {
    $cpu = (Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average
    $ts = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    Set-Content -Path $Out -Value ("{0} {1}" -f $ts, $cpu) -NoNewline
    Add-Content -Path $History -Value ("{0},{1}" -f $ts, $cpu)
    Start-Sleep -Seconds 15
}
