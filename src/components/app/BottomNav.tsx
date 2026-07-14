import Link from "next/link";

type BottomNavProps = {
  active: "home" | "roleplay" | "library" | "review";
};

// S04/S08 하단 네비. 롤플레이는 저장된 표현에서 세션을 시작하므로(POST /practice/sessions,
// #61) 탭은 표현을 고르는 /library로 보낸다. 표현 없음 empty-state 게이트(s12.md US1 AC4)는
// 아직 TBD.
const items = [
  { key: "home", label: "홈", href: "/home" },
  { key: "roleplay", label: "롤플레이", href: "/library" },
  { key: "library", label: "기록", href: "/library" },
  { key: "review", label: "복습", href: "/review" },
] as const;

export function BottomNav({ active }: BottomNavProps) {
  return (
    <nav className="bottom-nav" aria-label="하단 메뉴">
      {items.map((item) => (
        <Link
          key={item.key}
          className={`bottom-nav-item${item.key === active ? " is-active" : ""}`}
          href={item.href}
        >
          {item.label}
        </Link>
      ))}
    </nav>
  );
}
