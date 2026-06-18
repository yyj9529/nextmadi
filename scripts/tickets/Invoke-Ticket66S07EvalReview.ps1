param(
    [switch] $SkipIssueLookup
)

$ErrorActionPreference = "Continue"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$validator = Join-Path $PSScriptRoot "Test-S07EvalCases.ps1"
$casesPath = Join-Path $repoRoot "eval\s07-analysis\test_cases.json"

Push-Location $repoRoot
try {
    Write-Output "== #66 required context =="
    git status --short --branch

    if (-not $SkipIssueLookup) {
        if (Get-Command gh -ErrorAction SilentlyContinue) {
            gh issue view 66 --comments --repo yyj9529/nextmadi
        } else {
            Write-Output "[ticket-66] GitHub CLI not found; read issue #66 in browser before review."
        }
    }

    Write-Output ""
    Write-Output "== #66 cheap validation =="
    powershell -NoProfile -ExecutionPolicy Bypass -File $validator -CasesPath $casesPath -ExpectedCount 20
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    Write-Output ""
    Write-Output "== gated/live command, not run by this script =="
    Write-Output "python eval/s07-analysis/run_eval.py --trials 3 --prompt-version v1"
    Write-Output "Run the live eval only after explicit owner approval because it calls Anthropic and creates cost."
} finally {
    Pop-Location
}
