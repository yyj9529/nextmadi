# PreToolUse(Edit|Write) 가드: prompts/ 아래 .md의 frontmatter 필수 필드 손상을 감지하면 승인 프롬프트를 강제한다.
# 필수 필드(prompt loader, #28): feature, prompt_version, model, output_schema, created.
# frontmatter 영역을 건드리는 편집(prompt_version/output_schema 토큰 포함)에서만 동작 → 본문 편집 오탐 방지.
# 관련 티켓: #58 Roleplay Prompt v1, #28 Prompt Loader.

$ErrorActionPreference = "SilentlyContinue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$stdin = [Console]::In.ReadToEnd()
$text = (($args -join " ") + "`n" + $stdin)
# 입력은 JSON이라 줄바꿈이 이스케이프(\n)로 들어온다. 줄 단위 정규식을 위해 실제 줄바꿈으로 정규화.
$text = $text -replace '\\r\\n', "`n" -replace '\\n', "`n"

# prompts/ 경로 대상이 아니면 통과
$targetsPrompt = ($text -match "prompts/") -or ($text -match "prompts\\")
if (-not $targetsPrompt) { exit 0 }

# frontmatter 영역을 건드리는 편집인지(강한 신호 필드가 등장하는지)
$touchesFrontmatter = ($text -match "prompt_version") -or ($text -match "output_schema")
if (-not $touchesFrontmatter) { exit 0 }

# 5개 필수 필드 존재 여부
$required = @("feature", "prompt_version", "model", "output_schema", "created")
$missing = @()
foreach ($f in $required) {
    if ($text -notmatch ("(?m)^\s*" + [regex]::Escape($f) + "\s*:")) {
        $missing += $f
    }
}

if ($missing.Count -gt 0) {
    $reason = "[prompt-frontmatter-guard] A prompts/ file looks like it is dropping required frontmatter (likely missing: " + ($missing -join ", ") + "). The prompt loader (#28) requires feature/prompt_version/model/output_schema/created. Keep all five fields when editing."
    $out = @{
        hookSpecificOutput = @{
            hookEventName            = "PreToolUse"
            permissionDecision       = "ask"
            permissionDecisionReason = $reason
        }
    }
    $out | ConvertTo-Json -Compress -Depth 5
    exit 0
}

exit 0
