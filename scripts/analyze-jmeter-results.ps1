[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$JtlPath,

    [Parameter(Mandatory = $false)]
    [string]$OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-SortedQuantile {
    param(
        [Parameter(Mandatory = $true)]
        [double[]]$SortedValues,

        [Parameter(Mandatory = $true)]
        [ValidateRange(0, 1)]
        [double]$Quantile
    )

    if ($SortedValues.Count -eq 0) {
        return $null
    }

    $rank = [Math]::Max(1, [Math]::Ceiling($Quantile * $SortedValues.Count))
    return $SortedValues[$rank - 1]
}

function Convert-ToDouble {
    param([object]$Value)

    $parsed = 0.0
    if ([double]::TryParse([string]$Value, [Globalization.NumberStyles]::Float,
            [Globalization.CultureInfo]::InvariantCulture, [ref]$parsed)) {
        return $parsed
    }
    return $null
}

$groups = @{}
Import-Csv -LiteralPath $JtlPath | ForEach-Object {
    $elapsed = Convert-ToDouble $_.elapsed
    $label = [string]$_.label
    if ($null -eq $elapsed -or [string]::IsNullOrWhiteSpace($label)) {
        return
    }

    if (-not $groups.ContainsKey($label)) {
        $groups[$label] = [pscustomobject]@{
            Elapsed = [Collections.Generic.List[double]]::new()
            Samples = 0
            Errors = 0
            SumElapsed = 0.0
            StartTimestamp = [double]::PositiveInfinity
            EndTimestamp = [double]::NegativeInfinity
        }
    }

    $group = $groups[$label]
    $group.Elapsed.Add($elapsed)
    $group.Samples++
    $group.SumElapsed += $elapsed
    if ([string]$_.success -ne "true") {
        $group.Errors++
    }

    $timestamp = Convert-ToDouble $_.timeStamp
    if ($null -ne $timestamp) {
        $group.StartTimestamp = [Math]::Min($group.StartTimestamp, $timestamp)
        $group.EndTimestamp = [Math]::Max($group.EndTimestamp, $timestamp + $elapsed)
    }
}

if ($groups.Count -eq 0) {
    throw "JTL contains no parseable samples: $JtlPath"
}

$summary = @(
    foreach ($label in @($groups.Keys | Sort-Object)) {
        $group = $groups[$label]
        $elapsedValues = $group.Elapsed.ToArray()
        [Array]::Sort($elapsedValues)
        $durationSeconds = 0.0
        if (-not [double]::IsPositiveInfinity($group.StartTimestamp)) {
            $durationSeconds = [Math]::Max(0.001,
                    ($group.EndTimestamp - $group.StartTimestamp) / 1000.0)
        }

        [pscustomobject]@{
            Label = $label
            Samples = $group.Samples
            Errors = $group.Errors
            ErrorRatePercent = [Math]::Round(($group.Errors * 100.0) / $group.Samples, 3)
            ThroughputRps = [Math]::Round($group.Samples / [Math]::Max(0.001, $durationSeconds), 3)
            AverageMs = [Math]::Round($group.SumElapsed / $group.Samples, 3)
            P50Ms = [Math]::Round((Get-SortedQuantile -SortedValues $elapsedValues -Quantile 0.50), 3)
            P95Ms = [Math]::Round((Get-SortedQuantile -SortedValues $elapsedValues -Quantile 0.95), 3)
            P99Ms = [Math]::Round((Get-SortedQuantile -SortedValues $elapsedValues -Quantile 0.99), 3)
        }
    }
)

$summary | Format-Table -AutoSize | Out-Host

if (-not [string]::IsNullOrWhiteSpace($OutputPath)) {
    $parent = Split-Path -Parent $OutputPath
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    $summary | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath $OutputPath -Encoding utf8
    Write-Output "Summary written to $OutputPath"
}
