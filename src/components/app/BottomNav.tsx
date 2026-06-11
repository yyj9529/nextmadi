import Link from "next/link";

import { MOCK_SESSION_ID } from "@/lib/mock-api";

type BottomNavProps = {
  active: "home" | "roleplay" | "library" | "review";
};

// S04/S08 하단 네비. 롤플레이 탭은 실제로는 세션 생성(POST /practice/sessions)
// 후 진입하지만, 목 패스에서는 고정 세션으로 라우팅한다.
const items = [
  { key: "home", label: "홈", href: "/home" },
  { key: "roleplay", label: "롤플레이", href: `/practice/${MOCK_SESSION_ID}` },
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
