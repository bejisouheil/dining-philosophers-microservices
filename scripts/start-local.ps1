# Lance tout le systeme en local, sans Docker : moniteur, superviseur et N philosophes.
#
#   .\scripts\start-local.ps1                         # protocole par jetons, 5 philosophes
#   .\scripts\start-local.ps1 -Mode supervised-naive  # superviseur naif (interblocage)
#   .\scripts\start-local.ps1 -Mode supervised-unsafe -RaceWindowMs 40
#   .\scripts\stop-local.ps1                          # tout arreter
#
# Tableau de bord : http://localhost:8090     Journaux : dossier .run
# (Fichier volontairement sans accents : Windows PowerShell 5.1 les lit mal sans BOM.)
param(
    [ValidateSet("spoiled", "supervised-naive", "supervised-unsafe", "supervised-locked", "token", "zookeeper")]
    [string]$Mode = "token",
    [int]$Count = 5,
    [int]$RaceWindowMs = 0,
    # Temps de reflexion maximal ; 0 = toujours affame (interblocage rapide en mode naif)
    [int]$ThinkMaxMs = 1500,
    [switch]$Build
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$version = "0.1.0-SNAPSHOT"
$runDir = Join-Path $root ".run"

if (Test-Path (Join-Path $runDir "pids.txt")) {
    Write-Host "Un systeme tourne deja : arret avant de relancer."
    & (Join-Path $PSScriptRoot "stop-local.ps1")
}

$monitorJar = "monitor-service\target\monitor-service-$version-exec.jar"
if ($Build -or -not (Test-Path $monitorJar)) {
    Write-Host "Compilation du projet (tests non relances)..."
    & .\mvnw.cmd -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw "La compilation a echoue." }
}

New-Item -ItemType Directory -Force $runDir | Out-Null
# Etat des jetons d'une execution precedente : on repart d'une table propre.
Remove-Item -Recurse -Force (Join-Path $runDir "state") -ErrorAction SilentlyContinue

function Start-Jar([string]$Name, [string]$Jar, [hashtable]$Environment) {
    foreach ($key in $Environment.Keys) { Set-Item -Path "Env:$key" -Value ([string]$Environment[$key]) }
    $process = Start-Process java -ArgumentList "-jar", $Jar `
        -RedirectStandardOutput (Join-Path $runDir "$Name.log") `
        -RedirectStandardError (Join-Path $runDir "$Name.err.log") `
        -WindowStyle Hidden -PassThru
    foreach ($key in $Environment.Keys) { Remove-Item -Path "Env:$key" }
    Add-Content (Join-Path $runDir "pids.txt") $process.Id
    Write-Host ("  {0,-16} PID {1}" -f $Name, $process.Id)
}

Write-Host "Demarrage en mode '$Mode' avec $Count philosophes :"
Start-Jar "monitor" $monitorJar @{ PORT = 8090 }
Start-Jar "supervisor" "supervisor-service\target\supervisor-service-$version-exec.jar" @{
    PORT = 8080; SUPERVISOR_FORKS = $Count; SUPERVISOR_RACE_WINDOW_MS = $RaceWindowMs
}
Start-Sleep -Seconds 4   # le moniteur doit etre pret pour recevoir les premiers evenements

for ($id = 0; $id -lt $Count; $id++) {
    Start-Jar "philosopher-$id" "philosopher-service\target\philosopher-service-$version-exec.jar" @{
        PORT                      = 8081 + $id
        PHILOSOPHER_MODE          = $Mode
        PHILOSOPHER_COUNT         = $Count
        PHILOSOPHER_THINK_MIN_MS  = [Math]::Min(500, $ThinkMaxMs)
        PHILOSOPHER_THINK_MAX_MS  = $ThinkMaxMs
        PHILOSOPHER_STATE_DIR     = (Join-Path $runDir "state")
        PHILOSOPHER_MONITOR_URL   = "http://localhost:8090"
        PHILOSOPHER_SUPERVISOR_URL = "http://localhost:8080"
    }
}

Write-Host ""
Write-Host "Tableau de bord : http://localhost:8090"
Write-Host "Etat d'un philosophe : http://localhost:8081/state"
Write-Host "Pour tout arreter : .\scripts\stop-local.ps1"
