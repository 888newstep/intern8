[CmdletBinding()]
param(
    [ValidateRange(1, [long]::MaxValue)]
    [long]$UserId = 1,
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$EnvironmentFile = (Join-Path $PSScriptRoot "..\.env.local")
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Read-EnvironmentValue {
    param([string]$Path, [string]$Name)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Environment file not found: $Path"
    }
    foreach ($line in Get-Content -LiteralPath $Path -Encoding utf8) {
        $trimmed = $line.Trim()
        if ($trimmed.StartsWith("#") -or $trimmed.IndexOf("=") -lt 1) {
            continue
        }
        $separator = $trimmed.IndexOf("=")
        if ($trimmed.Substring(0, $separator).Trim() -eq $Name) {
            return $trimmed.Substring($separator + 1).Trim()
        }
    }
    return $null
}

$password = [Environment]::GetEnvironmentVariable("DEMO_AUTH_PASSWORD", "Process")
if ([string]::IsNullOrWhiteSpace($password)) {
    $password = Read-EnvironmentValue ([IO.Path]::GetFullPath($EnvironmentFile)) "DEMO_AUTH_PASSWORD"
}
if ([string]::IsNullOrWhiteSpace($password) -or $password.StartsWith("your_")) {
    throw "DEMO_AUTH_PASSWORD is missing or still contains the example placeholder."
}

$endpoint = $BaseUrl.TrimEnd("/") + "/api/user/login"
$body = @{ userId = $UserId; password = $password } | ConvertTo-Json
try {
    $response = Invoke-RestMethod -Method Post -Uri $endpoint -ContentType "application/json" -Body $body
} catch {
    throw "Dev login failed at $endpoint. Ensure the application is running with the dev profile. $($_.Exception.Message)"
}

if ($null -eq $response -or -not $response.success -or [string]::IsNullOrWhiteSpace($response.data.token)) {
    $message = if ($null -ne $response) { $response.msg } else { "empty response" }
    throw "Dev login was rejected: $message"
}

[Environment]::SetEnvironmentVariable("JWT_TOKEN", [string]$response.data.token, "Process")
Write-Output "JWT_TOKEN is set for user $UserId in the current PowerShell process."
