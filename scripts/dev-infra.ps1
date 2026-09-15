# 本地启动 SoarerAlert 依赖服务的 PowerShell 脚本。
param(
    [switch]$WithMcp
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot

$composeArgs = if ($WithMcp) {
    @("--profile", "mcp", "up", "-d", "--wait")
} else {
    @("up", "-d", "--wait")
}

# Compose validates app variables even when the app profile is disabled.
$infraPlaceholders = @{
    "DASHSCOPE_API_KEY" = "infra-placeholder-not-used"
    "AUTH_ADMIN_USERNAME" = "infra-admin-placeholder"
    "AUTH_ADMIN_INITIAL_PASSWORD" = "infra-admin-initial-placeholder"
}
$originalValues = @{}
foreach ($name in $infraPlaceholders.Keys) {
    $originalValues[$name] = [Environment]::GetEnvironmentVariable($name)
    if ([string]::IsNullOrWhiteSpace($originalValues[$name])) {
        Set-Item -Path "Env:$name" -Value $infraPlaceholders[$name]
    }
}

try {
    & docker compose @composeArgs
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose up failed with exit code $LASTEXITCODE."
    }

    & docker compose ps
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose ps failed with exit code $LASTEXITCODE."
    }
} finally {
    foreach ($name in $infraPlaceholders.Keys) {
        if ($null -eq $originalValues[$name]) {
            Remove-Item -Path "Env:$name" -ErrorAction SilentlyContinue
        } else {
            Set-Item -Path "Env:$name" -Value $originalValues[$name]
        }
    }
}
