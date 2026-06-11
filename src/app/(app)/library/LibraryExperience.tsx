"use client";

import { useState } from "react";
import Link from "next/link";

import { SearchIcon } from "@/components/app/icons";
import { mockLibraryItems } from "@/lib/mock-api";

// S08 내 기록 (라이브러리).
// 실제 구현: 검색어 300ms 디바운스 후 GET /expressions?q={keyword},
// 무한 스크롤은 cursor 페이지네이션. 목 패스: 로컬 필터링.
export function LibraryExperience() {
  const [keyword, setKeyword] = useState("");

  const normalized = keyword.trim().toLowerCase();
  const items =
    normalized.length === 0
      ? mockLibraryItems
      : mockLibraryItems.filter(
          (item) =>
            item.original_situation.toLowerCase().includes(normalized) ||
            item.english_text.toLowerCase().includes(normalized),
        );

  return (
    <>
      <header className="app-topbar">
        <h1 className="app-logo">내 기록</h1>
        <span className="library-count">📚 {mockLibraryItems.length}권</span>
      </header>

      <div className="library-search" role="search">
        <SearchIcon />
        <input
          className="library-search-input"
          type="search"
          placeholder="한국어 또는 영어로 검색"
          value={keyword}
          onChange={(event) => setKeyword(event.target.value)}
        />
      </div>

      {items.length > 0 ? (
        <ul className="library-list">
          {items.map((item) => (
            <li key={item.id}>
              <Link className="library-card" href={`/expression/${item.id}`}>
                <span className="library-card-situation">
                  “{item.original_situation}”
                </span>
                <span className="library-card-english">
                  {item.english_text}
                </span>
                <span className="library-card-meta">{item.relative_time}</span>
              </Link>
            </li>
          ))}
        </ul>
      ) : (
        <div className="library-empty">
          <p className="library-empty-title">
            ‘{keyword}’와 일치하는 표현이 없어요
          </p>
          <button
            className="library-empty-clear"
            type="button"
            onClick={() => setKeyword("")}
          >
            검색어 지우기
          </button>
        </div>
      )}
    </>
  );
}
