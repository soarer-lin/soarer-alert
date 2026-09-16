# 构建演示环境压缩包的 PowerShell 脚本。
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$SkipPackage
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot

if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $javacCommand = Get-Command javac -ErrorAction SilentlyContinue
    if ($javacCommand) {
        $JavaHome = Split-Path -Parent (Split-Path -Parent $javacCommand.Source)
    }
}

if (-not (Test-Path -LiteralPath (Join-Path $JavaHome "bin\javac.exe"))) {
    throw "JDK not found. Set JAVA_HOME or pass -JavaHome <path-to-jdk>."
}
$env:JAVA_HOME = $JavaHome
$env:Path = "$JavaHome\bin;$env:Path"

$mavenSettings = if (Test-Path -LiteralPath (Join-Path $projectRoot "maven-settings.local.xml")) {
    "maven-settings.local.xml"
} else {
    ".mvn\maven-settings.xml"
}

if (-not $SkipPackage) {
    Write-Host "==> [1/4] mvn package (skip tests)" -ForegroundColor Cyan
    & mvn -s $mavenSettings -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw "Maven package failed with exit code $LASTEXITCODE." }
} else {
    Write-Host "==> [1/4] mvn package skipped" -ForegroundColor DarkGray
}

Write-Host "==> [2/4] compile container healthcheck" -ForegroundColor Cyan
$healthcheckOut = Join-Path $projectRoot "target\healthcheck"
New-Item -ItemType Directory -Force -Path $healthcheckOut | Out-Null
& javac -encoding UTF-8 -d $healthcheckOut (Join-Path $projectRoot "docker\HealthCheck.java")
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE." }

$jarPath = Join-Path $projectRoot "target\soarer-alert-service-1.0-SNAPSHOT.jar"
if (-not (Test-Path -LiteralPath $jarPath)) {
    throw "Jar not found: $jarPath"
}

Write-Host "==> [3/4] stage image context and upload bundle" -ForegroundColor Cyan
$distRoot = Join-Path $projectRoot "dist"
$bundleRoot = Join-Path $distRoot "soarer-alert-demo"
$imageRoot = Join-Path $bundleRoot "image"

if (Test-Path -LiteralPath $bundleRoot) {
    Remove-Item -LiteralPath $bundleRoot -Recurse -Force
}

$dirs = @(
    $imageRoot,
    (Join-Path $imageRoot "healthcheck"),
    (Join-Path $bundleRoot "docker\postgres"),
    (Join-Path $bundleRoot "docker\prometheus")
)
foreach ($dir in $dirs) {
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
}

Copy-Item -LiteralPath (Join-Path $projectRoot "Dockerfile.demo") -Destination (Join-Path $imageRoot "Dockerfile")
Copy-Item -LiteralPath $jarPath -Destination (Join-Path $imageRoot "app.jar")
Copy-Item -LiteralPath (Join-Path $healthcheckOut "HealthCheck.class") -Destination (Join-Path $imageRoot "healthcheck\HealthCheck.class")
Copy-Item -LiteralPath (Join-Path $projectRoot "docker-compose.demo.yml") -Destination (Join-Path $bundleRoot "docker-compose.demo.yml")
Copy-Item -LiteralPath (Join-Path $projectRoot "docker\postgres\init.sql") -Destination (Join-Path $bundleRoot "docker\postgres\init.sql")
Copy-Item -LiteralPath (Join-Path $projectRoot "docker\prometheus\prometheus-demo.yml") -Destination (Join-Path $bundleRoot "docker\prometheus\prometheus-demo.yml")

Write-Host "==> [4/4] create zip" -ForegroundColor Cyan
$zipPath = Join-Path $distRoot "soarer-alert-demo.zip"
if (Test-Path -LiteralPath $zipPath) {
    Remove-Item -LiteralPath $zipPath -Force
}
Compress-Archive -Path $bundleRoot -DestinationPath $zipPath -CompressionLevel Optimal

$zipSizeMb = [math]::Round((Get-Item -LiteralPath $zipPath).Length / 1MB, 1)
Write-Host ""
Write-Host "Demo bundle created:" -ForegroundColor Green
Write-Host "  $zipPath ($zipSizeMb MB)" -ForegroundColor Green
Write-Host ""
Write-Host "Upload it to the server, then follow docs/DEMO-DEPLOY.md." -ForegroundColor Yellow
