$ErrorActionPreference = "SilentlyContinue"

$stdin = [Console]::In.ReadToEnd()
$text = (($args -join " ") + "`n" + $stdin)

$patterns = @(
    "eval/s07-analysis/run_eval.py",
    "eval\\s07-analysis\\run_eval.py",
    "ANTHROPIC_API_KEY",
    "OPENAI_API_KEY",
    "Anthropic",
    "OpenAI",
    "live eval",
    "--trials"
)

foreach ($pattern in $patterns) {
    if ($text -match [regex]::Escape($pattern)) {
        Write-Output "[approval-check] This looks like live eval/provider/API work. Per SECURITY.md, confirm owner approval before running anything that uses secrets, external AI APIs, or creates cost."
        exit 0
    }
}

exit 0
