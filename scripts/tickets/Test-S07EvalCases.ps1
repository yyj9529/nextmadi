param(
    [string] $CasesPath = "eval/s07-analysis/test_cases.json",
    [int] $ExpectedCount = 20
)

$ErrorActionPreference = "Stop"
$errors = @()

if (-not (Test-Path -LiteralPath $CasesPath)) {
    Write-Output "[s07-cases] Missing file: $CasesPath"
    exit 1
}

try {
    $raw = Get-Content -Raw -Encoding UTF8 -LiteralPath $CasesPath
    $data = $raw | ConvertFrom-Json -ErrorAction Stop
} catch {
    Write-Output "[s07-cases] Invalid JSON: $($_.Exception.Message)"
    exit 1
}

if ($null -eq $data.cases) {
    $errors += "Top-level 'cases' is missing."
} elseif (-not ($data.cases -is [System.Array])) {
    $errors += "Top-level 'cases' must be an array."
}

if ($errors.Count -eq 0) {
    $cases = @($data.cases)

    if ($ExpectedCount -gt 0 -and $cases.Count -ne $ExpectedCount) {
        $errors += "Expected $ExpectedCount cases, found $($cases.Count)."
    }

    $seenIds = @{}
    $requiredFields = @("id", "input_text", "tone_intent", "category", "expected_behaviors", "expected_failure_modes")

    foreach ($case in $cases) {
        $props = @($case.PSObject.Properties.Name)
        $caseId = if ($props -contains "id") { [string] $case.id } else { "<missing-id>" }

        foreach ($field in $requiredFields) {
            if (-not ($props -contains $field)) {
                $errors += "$caseId missing field '$field'."
            }
        }

        if ($props -contains "id") {
            if ($caseId -notmatch "^s07_\d{3}$") {
                $errors += "$caseId has invalid id format; expected s07_###."
            }
            if ($seenIds.ContainsKey($caseId)) {
                $errors += "Duplicate case id: $caseId."
            }
            $seenIds[$caseId] = $true
        }

        if (($props -contains "category") -and [string]::IsNullOrWhiteSpace([string] $case.category)) {
            $errors += "$caseId has empty category."
        }

        if (($props -contains "input_text") -and [string]::IsNullOrWhiteSpace([string] $case.input_text)) {
            $errors += "$caseId has empty input_text."
        }

        if ($props -contains "expected_behaviors") {
            $behaviors = @($case.expected_behaviors)
            if ($behaviors.Count -eq 0) {
                $errors += "$caseId expected_behaviors must contain at least one item."
            }
        }

        if ($props -contains "expected_failure_modes") {
            $failureModes = @($case.expected_failure_modes)
            if ($failureModes.Count -eq 0) {
                $errors += "$caseId expected_failure_modes must contain at least one item."
            }
        }
    }
}

if ($errors.Count -gt 0) {
    Write-Output "[s07-cases] FAIL"
    $errors | ForEach-Object { Write-Output "- $_" }
    exit 1
}

$categoryCounts = @($data.cases) |
    Group-Object -Property category |
    Sort-Object -Property Name |
    ForEach-Object { "$($_.Name)=$($_.Count)" }

Write-Output "[s07-cases] PASS"
Write-Output "Case count: $(@($data.cases).Count)"
Write-Output "Categories: $($categoryCounts -join ', ')"
Write-Output "Raw input_text was not printed."
