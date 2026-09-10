[CmdletBinding()]
param(
    [string]$EnvironmentFile = (Join-Path $PSScriptRoot "..\.env.local"),
    [switch]$SkipTests,
    [switch]$SkipTopologyCheck,
    [switch]$CheckMilvus
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Import-EnvironmentFile {
    param([string]$Path)

    $resolvedPath = [IO.Path]::GetFullPath($Path)
    if (-not (Test-Path -LiteralPath $resolvedPath -PathType Leaf)) {
        throw "Environment file not found: $resolvedPath. Create it from .env.example."
    }

    foreach ($line in Get-Content -LiteralPath $resolvedPath -Encoding utf8) {
        $trimmed = $line.Trim()
        if ([string]::IsNullOrWhiteSpace($trimmed) -or $trimmed.StartsWith("#")) {
            continue
        }

        $separator = $trimmed.IndexOf("=")
        if ($separator -lt 1) {
            throw "Invalid environment entry in $resolvedPath. Expected NAME=value."
        }
        $name = $trimmed.Substring(0, $separator).Trim()
        $value = $trimmed.Substring($separator + 1).Trim()
        if ($name -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {
            throw "Invalid environment variable name in ${resolvedPath}: $name"
        }

        # 仅写入当前 PowerShell 进程及其子进程，不修改系统环境变量。
        [Environment]::SetEnvironmentVariable($name, $value, "Process")
    }
}

function Assert-RequiredEnvironment {
    param([string[]]$Names)
    $missing = @()
    foreach ($name in $Names) {
        $value = [Environment]::GetEnvironmentVariable($name, "Process")
        if ([string]::IsNullOrWhiteSpace($value) -or $value.StartsWith("your_")) {
            $missing += $name
        }
    }
    if ($missing.Count -gt 0) {
        throw "Missing or placeholder environment variables: $($missing -join ', ')"
    }
}

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
Import-EnvironmentFile $EnvironmentFile
Assert-RequiredEnvironment @(
    "MYSQL_USERNAME",
    "MYSQL_PASSWORD",
    "RABBITMQ_HOST",
    "RABBITMQ_USERNAME",
    "RABBITMQ_PASSWORD",
    "JWT_SECRET",
    "DEMO_AUTH_ENABLED",
    "DEMO_AUTH_PASSWORD",
    "DEMO_AUTH_ALLOWED_USER_IDS"
)

if ($env:JWT_SECRET.Length -lt 32) {
    throw "JWT_SECRET must contain at least 32 characters for HS256."
}
if ($env:DEMO_AUTH_ENABLED -ne "true") {
    throw "DEMO_AUTH_ENABLED must be true for the local dev launcher."
}
if ($env:DEMO_AUTH_ALLOWED_USER_IDS -notmatch '^\s*[1-9][0-9]*(\s*,\s*[1-9][0-9]*)*\s*$') {
    throw "DEMO_AUTH_ALLOWED_USER_IDS must be a comma-separated list of positive user IDs."
}

if (-not $SkipTopologyCheck) {
    $topologyScript = Join-Path $PSScriptRoot "check-hybrid-topology.ps1"
    if ($CheckMilvus) {
        & $topologyScript -RequireMilvus
    } else {
        & $topologyScript
    }
}

$maven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if ($null -eq $maven) {
    $maven = Get-Command mvn -ErrorAction SilentlyContinue
}
if ($null -eq $maven) {
    throw "Maven was not found on PATH."
}

$java = Get-Command java.exe -ErrorAction SilentlyContinue
if ($null -eq $java) {
    throw "Java was not found on PATH. JDK 17 is required."
}

Push-Location $repositoryRoot
try {
    Write-Output "Building application..."
    if ($SkipTests) {
        & $maven.Source package -DskipTests
    } else {
        & $maven.Source package
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Maven build failed with exit code $LASTEXITCODE."
    }

    $jarPath = Join-Path $repositoryRoot "intern-base-web\target\intern-base.jar"
    if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
        throw "Application jar was not produced: $jarPath"
    }

    Write-Output "Starting application at http://127.0.0.1:8080 with dev profile..."
    & $java.Source -jar $jarPath --spring.profiles.active=dev
    if ($LASTEXITCODE -ne 0) {
        throw "Application exited with code $LASTEXITCODE."
    }
} finally {
    Pop-Location
}
