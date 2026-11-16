# Arrete les services lances par start-local.ps1 (et seulement ceux-la).
$root = Split-Path -Parent $PSScriptRoot
$pidFile = Join-Path $root ".run\pids.txt"

if (-not (Test-Path $pidFile)) {
    Write-Host "Rien a arreter."
    return
}

$stopped = 0
foreach ($processId in Get-Content $pidFile) {
    $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if ($process -and $process.ProcessName -eq "java") {
        Stop-Process -Id $processId -Force
        $stopped++
    }
}
Remove-Item $pidFile
Write-Host "$stopped service(s) arrete(s)."
