[CmdletBinding()]
param(
    [string]$MySqlHost,
    [int]$MySqlPort,
    [string]$RedisHost,
    [int]$RedisPort,
    [string]$RabbitMqHost,
    [int]$RabbitMqPort,
    [string]$MilvusHost,
    [int]$MilvusPort,
    [switch]$RequireMilvus
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-HostValue {
    param([string]$ParameterValue, [string]$EnvironmentValue, [string]$DefaultValue)
    if (-not [string]::IsNullOrWhiteSpace($ParameterValue)) { return $ParameterValue.Trim() }
    if (-not [string]::IsNullOrWhiteSpace($EnvironmentValue)) { return $EnvironmentValue.Trim() }
    return $DefaultValue
}

function Resolve-PortValue {
    param([int]$ParameterValue, [string]$EnvironmentValue, [int]$DefaultValue, [string]$Name)
    if ($ParameterValue -gt 0) { return $ParameterValue }
    if (-not [string]::IsNullOrWhiteSpace($EnvironmentValue)) {
        $parsed = 0
        if (-not [int]::TryParse($EnvironmentValue, [ref]$parsed) -or $parsed -lt 1 -or $parsed -gt 65535) {
            throw "$Name must be a TCP port between 1 and 65535."
        }
        return $parsed
    }
    return $DefaultValue
}

function Test-Endpoint {
    param([string]$Name, [string]$HostName, [int]$Port)
    $reachable = Test-NetConnection -ComputerName $HostName -Port $Port `
        -InformationLevel Quiet -WarningAction SilentlyContinue
    [pscustomobject]@{
        Service = $Name
        Endpoint = "$HostName`:$Port"
        Reachable = [bool]$reachable
    }
}

$resolvedMySqlHost = Resolve-HostValue $MySqlHost $env:MYSQL_HOST "127.0.0.1"
$resolvedMySqlPort = Resolve-PortValue $MySqlPort $env:MYSQL_PORT 3306 "MYSQL_PORT"
$resolvedRedisHost = Resolve-HostValue $RedisHost $env:REDIS_HOST "127.0.0.1"
$resolvedRedisPort = Resolve-PortValue $RedisPort $env:REDIS_PORT 6379 "REDIS_PORT"
$resolvedRabbitHost = Resolve-HostValue $RabbitMqHost $env:RABBITMQ_HOST ""
$resolvedRabbitPort = Resolve-PortValue $RabbitMqPort $env:RABBITMQ_PORT 5672 "RABBITMQ_PORT"
$resolvedMilvusHost = Resolve-HostValue $MilvusHost $env:MILVUS_HOST ""
$resolvedMilvusPort = Resolve-PortValue $MilvusPort $env:MILVUS_PORT 19530 "MILVUS_PORT"

if ([string]::IsNullOrWhiteSpace($resolvedRabbitHost)) {
    throw "RABBITMQ_HOST or -RabbitMqHost is required for the cloud RabbitMQ check."
}
if ($RequireMilvus -and [string]::IsNullOrWhiteSpace($resolvedMilvusHost)) {
    throw "MILVUS_HOST or -MilvusHost is required when -RequireMilvus is used."
}

$results = @(
    Test-Endpoint "MySQL (local)" $resolvedMySqlHost $resolvedMySqlPort
    Test-Endpoint "Redis (local)" $resolvedRedisHost $resolvedRedisPort
    Test-Endpoint "RabbitMQ (cloud)" $resolvedRabbitHost $resolvedRabbitPort
)
if (-not [string]::IsNullOrWhiteSpace($resolvedMilvusHost)) {
    $results += Test-Endpoint "Milvus (cloud)" $resolvedMilvusHost $resolvedMilvusPort
}

$results | Format-Table -AutoSize
$failed = @($results | Where-Object { -not $_.Reachable })
if ($failed.Count -gt 0) {
    $failedNames = ($failed | ForEach-Object { $_.Service }) -join ", "
    throw "Topology check failed: $failedNames. Check services, cloud security groups and Windows firewall."
}

Write-Output "Hybrid topology TCP checks passed. Application credentials are validated during startup."
