# Prepare le poste puis lance le projet : verifie Git et Java 21 (les installe s'ils manquent),
# compile, demarre le systeme et ouvre le tableau de bord.
#
#   .\scripts\setup-and-run.ps1                          # protocole par jetons
#   .\scripts\setup-and-run.ps1 -Mode supervised-naive   # une autre variante
#   .\scripts\setup-and-run.ps1 -Test                    # lance aussi les 179 tests (long)
#   .\scripts\setup-and-run.ps1 -Docker                  # lance avec Docker Compose au lieu de Java
#
# Ou double-cliquer sur setup-and-run.cmd. Arret : .\scripts\stop-local.ps1
# (Fichier volontairement sans accents : Windows PowerShell 5.1 les lit mal sans BOM.)
param(
    [ValidateSet("spoiled", "supervised-naive", "supervised-unsafe", "supervised-locked", "token", "zookeeper")]
    [string]$Mode = "token",
    [switch]$Test,
    [switch]$Docker,
    [switch]$NoBrowser
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Step([string]$Text) { Write-Host ""; Write-Host "==> $Text" -ForegroundColor Cyan }
function Ok([string]$Text)   { Write-Host "    OK  $Text" -ForegroundColor Green }
function Warn([string]$Text) { Write-Host "    !!  $Text" -ForegroundColor Yellow }

# Recharge le PATH de la session apres une installation (sinon la commande reste introuvable).
function Update-SessionPath {
    $machine = [Environment]::GetEnvironmentVariable("Path", "Machine")
    $user = [Environment]::GetEnvironmentVariable("Path", "User")
    $env:Path = "$machine;$user"
}

function Test-Command([string]$Name) { [bool](Get-Command $Name -ErrorAction SilentlyContinue) }

function Get-JavaMajor {
    if (-not (Test-Command "java")) { return 0 }
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $line = (& java -version 2>&1 | Select-Object -First 1).ToString()
    $ErrorActionPreference = $previous
    if ($line -match '"(\d+)(\.\d+)*') { return [int]$Matches[1] }
    return 0
}

function Install-WithWinget([string]$Id, [string]$Label) {
    if (-not (Test-Command "winget")) {
        throw "$Label est absent et 'winget' est introuvable. Installe $Label a la main puis relance ce script."
    }
    Write-Host "    Installation de $Label (une fenetre d'autorisation Windows peut s'ouvrir)..."
    & winget install --id $Id -e --silent --accept-package-agreements --accept-source-agreements
    Update-SessionPath
}

Step "1/5 Verification de l'environnement"

# --- Git ---
if (Test-Command "git") { Ok ("Git " + ((git --version) -replace "git version ", "")) }
else {
    Install-WithWinget "Git.Git" "Git"
    if (-not (Test-Command "git")) { throw "Git installe mais introuvable : ferme et rouvre le terminal puis relance." }
    Ok "Git installe"
}

# --- Java 21 ---
$javaMajor = Get-JavaMajor
if ($javaMajor -ge 21) { Ok "Java $javaMajor" }
else {
    if ($javaMajor -gt 0) { Warn "Java $javaMajor detecte : il faut Java 21 ou plus." } else { Warn "Java est absent." }
    Install-WithWinget "EclipseAdoptium.Temurin.21.JDK" "Java 21 (Temurin)"
    # Le JDK est parfois installe sans que le PATH de la session ne le voie : on le cherche.
    if ((Get-JavaMajor) -lt 21) {
        $jdk = Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -Filter "jdk-21*" -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending | Select-Object -First 1
        if ($jdk) { $env:JAVA_HOME = $jdk.FullName; $env:Path = "$($jdk.FullName)\bin;$env:Path" }
    }
    if ((Get-JavaMajor) -lt 21) { throw "Java 21 installe mais introuvable : ferme et rouvre le terminal puis relance." }
    Ok "Java 21 installe"
}

# --- Docker (optionnel hors -Docker et mode zookeeper) ---
$needDocker = $Docker -or ($Mode -eq "zookeeper")
$dockerReady = $false
if (Test-Command "docker") {
    $previous = $ErrorActionPreference; $ErrorActionPreference = "Continue"
    & docker info *> $null
    $dockerReady = ($LASTEXITCODE -eq 0)
    $ErrorActionPreference = $previous
}
if ($dockerReady) { Ok "Docker demarre" }
elseif ($needDocker) {
    if (-not (Test-Command "docker")) {
        Install-WithWinget "Docker.DockerDesktop" "Docker Desktop"
        throw "Docker Desktop est installe : redemarre le PC, lance Docker Desktop, puis relance ce script."
    }
    throw "Docker est installe mais pas demarre : lance Docker Desktop, attends l'icone verte, puis relance."
}
else { Warn "Docker absent ou arrete : sans importance pour ce mode (utile pour -Docker, zookeeper et les tests ZooKeeper/Kafka)." }

Step "2/5 Verification du depot"
if (-not (Test-Path (Join-Path $root "mvnw.cmd"))) { throw "Lance ce script depuis le depot clone (mvnw.cmd introuvable)." }
$branch = (git rev-parse --abbrev-ref HEAD) 2>$null
Ok "Depot : $root (branche $branch)"
if ($branch -and $branch -ne "master") { Warn "Tu n'es pas sur master. Pour y passer : git checkout master ; git pull" }

if ($Docker) {
    Step "3/5 Docker Compose"
    $env:MODE = $Mode
    & docker compose up --build -d
    if ($LASTEXITCODE -ne 0) { throw "docker compose a echoue." }
    Step "4/5 Attente du tableau de bord"
}
else {
    if ($Test) {
        Step "3/5 Compilation et tests (plusieurs minutes la premiere fois)"
        & .\mvnw.cmd verify
        if ($LASTEXITCODE -ne 0) { throw "Des tests ont echoue." }
    }
    else {
        Step "3/5 Compilation (sans les tests ; ajoute -Test pour les lancer)"
        & .\mvnw.cmd -q -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw "La compilation a echoue." }
    }
    Ok "Compilation terminee"

    if ($Mode -eq "zookeeper") {
        Step "ZooKeeper (conteneur)"
        & docker rm -f dining-zookeeper *> $null
        & docker run -d --name dining-zookeeper -p 2181:2181 zookeeper:3.9 | Out-Null
        Start-Sleep -Seconds 8
        Ok "ZooKeeper demarre"
    }

    Step "4/5 Demarrage du systeme en mode '$Mode'"
    $extra = @{}
    if ($Mode -eq "supervised-naive") { $extra["ThinkMaxMs"] = 0 }
    if ($Mode -eq "supervised-unsafe") { $extra["RaceWindowMs"] = 40 }
    & (Join-Path $PSScriptRoot "start-local.ps1") -Mode $Mode @extra
}

# --- Attente du moniteur ---
$ready = $false
for ($i = 0; $i -lt 60; $i++) {
    try {
        $answer = Invoke-RestMethod -Uri "http://localhost:8090/api/snapshot" -TimeoutSec 2
        if ($answer) { $ready = $true; break }
    } catch { Start-Sleep -Seconds 2 }
}

Step "5/5 Resultat"
if ($ready) {
    Ok "Le systeme repond. Tableau de bord : http://localhost:8090"
    if (-not $NoBrowser) { Start-Process "http://localhost:8090" }
}
else { Warn "Le moniteur ne repond pas encore : regarde les journaux du dossier .run (ou docker compose logs)." }

Write-Host ""
if ($Docker) { Write-Host "Arret : docker compose down -v" }
else { Write-Host "Arret : .\scripts\stop-local.ps1" }
Write-Host "Autres variantes : .\scripts\setup-and-run.ps1 -Mode supervised-naive | supervised-unsafe | supervised-locked | token | spoiled | zookeeper"
