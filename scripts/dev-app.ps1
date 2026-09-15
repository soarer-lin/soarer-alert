param(
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot

if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $javaCommand = Get-Command java -ErrorAction SilentlyContinue
    if ($javaCommand) {
        $JavaHome = Split-Path -Parent (Split-Path -Parent $javaCommand.Source)
    }
}

if (-not (Test-Path -LiteralPath (Join-Path $JavaHome "bin\java.exe"))) {
    throw "JDK not found. Set JAVA_HOME or pass -JavaHome <path-to-jdk>."
}

if ([string]::IsNullOrWhiteSpace($env:DASHSCOPE_API_KEY)) {
    throw "Set DASHSCOPE_API_KEY in this PowerShell session before starting the app."
}

if ([string]::IsNullOrWhiteSpace($env:AUTH_ADMIN_USERNAME)) {
    throw "Set AUTH_ADMIN_USERNAME in this PowerShell session before starting the app."
}

if ([string]::IsNullOrWhiteSpace($env:AUTH_ADMIN_INITIAL_PASSWORD)) {
    throw "Set AUTH_ADMIN_INITIAL_PASSWORD in this PowerShell session before starting the app."
}

$env:JAVA_HOME = $JavaHome
$env:Path = "$JavaHome\bin;$env:Path"
$env:SPRING_PROFILES_ACTIVE = "local"
$env:POSTGRES_HOST = if ($env:POSTGRES_HOST) { $env:POSTGRES_HOST } else { "localhost" }
$env:POSTGRES_PORT = if ($env:POSTGRES_PORT) { $env:POSTGRES_PORT } else { "15432" }
$env:REDIS_HOST = if ($env:REDIS_HOST) { $env:REDIS_HOST } else { "localhost" }
$env:REDIS_PORT = if ($env:REDIS_PORT) { $env:REDIS_PORT } else { "16379" }
$env:RUSTFS_ENDPOINT = if ($env:RUSTFS_ENDPOINT) { $env:RUSTFS_ENDPOINT } else { "http://localhost:19000" }
$env:PROMETHEUS_BASE_URL = if ($env:PROMETHEUS_BASE_URL) { $env:PROMETHEUS_BASE_URL } else { "http://localhost:19090" }
$env:CLS_MCP_URL = if ($env:CLS_MCP_URL) { $env:CLS_MCP_URL } else { "http://localhost:13000" }

$mavenSettings = if (Test-Path -LiteralPath (Join-Path $projectRoot "maven-settings.local.xml")) {
    "maven-settings.local.xml"
} else {
    ".mvn\maven-settings.xml"
}

$goals = @("-s", $mavenSettings)
if ($SkipTests) {
    $goals += "-DskipTests"
}
$goals += "spring-boot:run"

& mvn @goals
