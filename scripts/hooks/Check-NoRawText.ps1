# PreToolUse(Edit|Write) 가드: 사용자 원문 텍스트를 로그/DB에 저장하려는 코드를 감지하면 승인 프롬프트를 강제한다.
# AI_PIPELINE.md / SECURITY.md: ai_request_logs 등에 raw user text 저장 금지(PII).
# 휴리스틱이라 오탐 가능 — 그래서 차단(deny)이 아니라 ask. 정당하면 사람이 승인한다.
# 관련 티켓: #27 ai_request_logs, #39 analysis, #60 turn pipeline, #62 result save.

$ErrorActionPreference = "SilentlyContinue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$stdin = [Console]::In.ReadToEnd()
$text = (($args -join " ") + "`n" + $stdin)

# 원문 텍스트 저장으로 의심되는 토큰
$rawTokens = @(
    "raw_user_text", "raw_input", "raw_text", "user_text", "input_text",
    "original_text", "prompt_text", "request_body", "utterance_text", "transcript_text"
)

$hasLogSink   = ($text -match "ai_request_logs") -or ($text -match "(?i)log\w*\.(info|debug|warn|trace)")
$hasRawToken  = $false
$matched      = ""
foreach ($t in $rawTokens) {
    if ($text -match [regex]::Escape($t)) { $hasRawToken = $true; $matched = $t; break }
}

# 명시적으로 위험한 단독 토큰
$explicit = ($text -match "raw_user_text") -or ($text -match "store.*raw.*(input|text|utterance)")

if ($explicit -or ($hasLogSink -and $hasRawToken)) {
    $reason = "[no-raw-text-guard] This looks like code that stores raw user text in logs/DB (token: $matched). Per AI_PIPELINE.md/SECURITY.md, ai_request_logs etc. must NOT store raw user text (PII) - keep only metadata/length/hash. Approve only if this is legitimate."
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
