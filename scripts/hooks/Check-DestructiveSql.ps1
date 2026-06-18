# PreToolUse(Bash) 가드: 파괴적 SQL을 감지하면 승인 프롬프트를 강제한다.
# 차단(deny)이 아니라 ask로 둔다 — migration은 정당할 수 있으므로 사람이 판단하게 한다.
# 관련 티켓: #14 Flyway, #16 Scheduled Jobs(hard delete), #24 Account Deletion.

$ErrorActionPreference = "SilentlyContinue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$stdin = [Console]::In.ReadToEnd()
$text = (($args -join " ") + "`n" + $stdin)

# 파괴적/불가역 SQL 패턴
$patterns = @(
    "DROP\s+TABLE",
    "DROP\s+DATABASE",
    "DROP\s+SCHEMA",
    "DROP\s+COLUMN",
    "TRUNCATE\s+",
    "ALTER\s+TABLE\s+\w+\s+DROP",
    "DELETE\s+FROM\s+\w+\s*;",          # WHERE 없는 DELETE (세미콜론 종료)
    "DELETE\s+FROM\s+\w+\s*$",          # WHERE 없는 DELETE (라인 끝)
    "flyway[\s\S]*clean",               # flyway clean = 전체 스키마 삭제
    "UPDATE\s+\w+\s+SET[\s\S]*;\s*$"    # WHERE 없어 보이는 대량 UPDATE는 확인
)

foreach ($pattern in $patterns) {
    if ($text -match $pattern) {
        $reason = "[destructive-sql-guard] This looks like destructive/irreversible SQL (pattern: $pattern). Per SECURITY.md, DB migrations need owner approval and are irreversible. Cross-check docs/data-model.md and confirm backup/rollback before proceeding."
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
}

exit 0
