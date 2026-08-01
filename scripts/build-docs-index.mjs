#!/usr/bin/env node
// PhraseLog 문서 인덱스 생성기.
//
// docs/**/*.md 와 루트 진입 문서(START_HERE, CLAUDE 등)를 읽어 단일 HTML로 묶는다.
// 왼쪽 sticky 사이드바에서 문서 → 소제목까지 바로 점프할 수 있게 하는 것이 목적.
//
//   npm run docs:index   →  docs/_site/index.html
//
// 문서가 늘거나 제목이 바뀌면 다시 돌리면 된다. 결과물은 생성물이라 커밋하지 않는다.

import { readFile, writeFile, mkdir, readdir, stat } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { marked } from "marked";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const OUT_DIR = path.join(ROOT, "docs", "_site");
const OUT_FILE = path.join(OUT_DIR, "index.html");

/** 루트에 흩어져 있는 진입 문서. 없으면 조용히 건너뛴다. */
const ROOT_DOCS = [
  "START_HERE.md",
  "CLAUDE.md",
  "AGENTS.md",
  "PROJECT_CONTEXT.md",
  "SECURITY.md",
  "README.md",
  "lessons.md",
];

/** 사이드바 그룹. 순서가 곧 화면 순서다. */
const GROUPS = [
  { id: "entry", label: "시작하기" },
  { id: "core", label: "핵심 스펙" },
  { id: "screens", label: "화면 스펙" },
  { id: "decisions", label: "결정 (ADR)" },
  { id: "api", label: "API" },
  { id: "exec-plans", label: "실행 계획" },
  { id: "reviews", label: "리뷰" },
  { id: "misc", label: "그 외" },
];

// ---------------------------------------------------------------- 유틸

const exists = async (p) => {
  try {
    await stat(p);
    return true;
  } catch {
    return false;
  }
};

/** docs/ 아래 모든 .md / openapi 파일을 재귀 수집. _site 는 자기 출력이라 제외. */
async function walk(dir, acc = []) {
  const entries = await readdir(dir, { withFileTypes: true });
  for (const entry of entries) {
    if (entry.name.startsWith(".") || entry.name === "_site") continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      await walk(full, acc);
    } else if (/\.(md|ya?ml)$/i.test(entry.name)) {
      acc.push(full);
    }
  }
  return acc;
}

const escapeHtml = (s) =>
  s
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");

/** 한글을 살리는 slug. 라틴/숫자/한글만 남기고 나머지는 하이픈. */
function slugify(text) {
  return (
    text
      .toLowerCase()
      .replace(/[`*_~[\]()#]/g, "")
      .trim()
      .replace(/[^\p{Letter}\p{Number}]+/gu, "-")
      .replace(/^-+|-+$/g, "") || "section"
  );
}

/** 첫 h1을 제목으로, 없으면 파일명. */
function extractTitle(markdown, relPath) {
  const m = markdown.match(/^#\s+(.+)$/m);
  if (m) return m[1].replace(/[`*_]/g, "").trim();
  return path.basename(relPath).replace(/\.(md|ya?ml)$/i, "");
}

/** 사이드바에 쓸 짧은 라벨. 긴 제목은 잘라 쓴다. */
function shortLabel(relPath, title) {
  const base = path.basename(relPath).replace(/\.(md|ya?ml)$/i, "");
  if (relPath.startsWith("docs/screens/")) return base.toUpperCase();
  if (relPath.startsWith("docs/decisions/")) {
    if (base === "INDEX") return "INDEX (한 줄 요약)";
    const num = base.match(/^(\d+)/);
    const rest = base.replace(/^\d+-/, "").replace(/-/g, " ");
    return num ? num[1] + " · " + rest : base;
  }
  if (
    relPath.startsWith("docs/exec-plans/") ||
    relPath.startsWith("docs/reviews/")
  ) {
    const dated = base.match(/^(\d{4})-(\d{2})-(\d{2})-(.+)$/);
    if (dated) return dated[2] + "/" + dated[3] + " · " + dated[4].replace(/-/g, " ");
    return base.replace(/-/g, " ");
  }
  return title.length > 40 ? title.slice(0, 38) + "…" : title;
}

function groupFor(relPath) {
  if (!relPath.startsWith("docs/")) return "entry";
  const rest = relPath.slice("docs/".length);
  if (!rest.includes("/")) return "core";
  const seg = rest.split("/")[0];
  if (GROUPS.some((g) => g.id === seg)) return seg;
  return "misc";
}

/** 그룹별 정렬 규칙. 실행 계획/리뷰는 최신이 위. */
function sortDocs(groupId, docs) {
  const byRel = (a, b) => a.rel.localeCompare(b.rel, "en", { numeric: true });
  if (groupId === "exec-plans" || groupId === "reviews") {
    return docs.sort((a, b) => byRel(b, a));
  }
  if (groupId === "decisions") {
    return docs.sort((a, b) => {
      const ai = a.rel.endsWith("INDEX.md") ? 0 : 1;
      const bi = b.rel.endsWith("INDEX.md") ? 0 : 1;
      return ai - bi || byRel(a, b);
    });
  }
  if (groupId === "entry") {
    return docs.sort(
      (a, b) =>
        ROOT_DOCS.indexOf(path.basename(a.rel)) -
        ROOT_DOCS.indexOf(path.basename(b.rel)),
    );
  }
  return docs.sort(byRel);
}

// ------------------------------------------------- 마크다운 → HTML

// 문서마다 heading id를 따로 만들어야 한 파일 안에서 id가 겹치지 않는다.
let currentDocId = "doc";
let currentHeadings = [];
let usedSlugs = new Set();

marked.use({
  gfm: true,
  breaks: false,
  renderer: {
    heading(token) {
      const inline = this.parser.parseInline(token.tokens);
      const plain = token.text.replace(/[`*_[\]()]/g, "").trim();
      let slug = slugify(plain);
      let n = 2;
      while (usedSlugs.has(slug)) slug = slugify(plain) + "-" + n++;
      usedSlugs.add(slug);
      const id = currentDocId + "--" + slug;
      // h2/h3만 사이드바 하위 목차로 노출한다. h4 이하는 너무 잘게 쪼개진다.
      if (token.depth === 2 || token.depth === 3) {
        currentHeadings.push({ id, depth: token.depth, text: plain });
      }
      return (
        "<h" + token.depth + ' id="' + id + '" class="doc-h">' +
        inline +
        "</h" + token.depth + ">\n"
      );
    },
  },
});

function renderMarkdown(docId, markdown) {
  currentDocId = docId;
  currentHeadings = [];
  usedSlugs = new Set();
  const html = marked.parse(markdown);
  return { html, headings: currentHeadings };
}

function renderYaml(docId, source) {
  return {
    html:
      '<h2 id="' + docId + '--source" class="doc-h">원문</h2>\n<pre class="yaml"><code>' +
      escapeHtml(source) +
      "</code></pre>",
    headings: [{ id: docId + "--source", depth: 2, text: "원문" }],
  };
}

// ---------------------------------------------------------------- 수집

async function collect() {
  const files = [];

  for (const name of ROOT_DOCS) {
    const full = path.join(ROOT, name);
    if (await exists(full)) files.push(full);
  }
  files.push(...(await walk(path.join(ROOT, "docs"))));

  const docs = [];
  for (const full of files) {
    const rel = path.relative(ROOT, full).split(path.sep).join("/");
    const source = await readFile(full, "utf8");
    const id = rel
      .replace(/^docs\//, "")
      .replace(/\.(md|ya?ml)$/i, "")
      .replace(/[^\p{Letter}\p{Number}/.-]+/gu, "-")
      .replace(/\//g, "-")
      .toLowerCase();
    const title = extractTitle(source, rel);
    const isYaml = /\.ya?ml$/i.test(rel);
    const { html, headings } = isYaml
      ? renderYaml(id, source)
      : renderMarkdown(id, source);

    docs.push({
      id,
      rel,
      title,
      label: shortLabel(rel, title),
      group: groupFor(rel),
      html,
      headings,
      // 검색용: 제목 + 소제목 + 본문 앞부분(너무 키우면 파일이 커진다)
      search: (title + " " + rel + " " + headings.map((h) => h.text).join(" ") + " " +
        source.replace(/\s+/g, " ").slice(0, 4000)).toLowerCase(),
    });
  }
  return docs;
}

// ---------------------------------------------------------------- 출력

function buildSidebar(docs) {
  let out = "";
  for (const group of GROUPS) {
    const inGroup = sortDocs(
      group.id,
      docs.filter((d) => d.group === group.id),
    );
    if (!inGroup.length) continue;
    out +=
      '<div class="nav-group" data-group="' + group.id + '">' +
      '<p class="nav-group-title">' + escapeHtml(group.label) +
      '<span class="nav-count">' + inGroup.length + "</span></p>";
    for (const doc of inGroup) {
      out +=
        '<a class="nav-doc" href="#' + doc.id + '" data-doc="' + doc.id + '" ' +
        'title="' + escapeHtml(doc.rel) + '">' + escapeHtml(doc.label) + "</a>";
      if (doc.headings.length) {
        out += '<div class="nav-subs" data-subs="' + doc.id + '">';
        for (const h of doc.headings) {
          out +=
            '<a class="nav-sub nav-sub-h' + h.depth + '" href="#' + h.id + '" ' +
            'data-target="' + h.id + '">' + escapeHtml(h.text) + "</a>";
        }
        out += "</div>";
      }
    }
    out += "</div>";
  }
  return out;
}

function buildArticles(docs) {
  return docs
    .map(
      (doc) =>
        '<article class="doc" id="' + doc.id + '" data-doc="' + doc.id + '" hidden>' +
        '<p class="doc-path">' + escapeHtml(doc.rel) + "</p>" +
        doc.html +
        "</article>",
    )
    .join("\n");
}

const STYLE = `
:root {
  --bg: #f7f8f7;
  --panel: #ffffff;
  --ink: #18313a;
  --muted: #63736f;
  --line: #e2e7e4;
  --accent: #1f7a5a;
  --accent-soft: #e7f4ee;
  --code-bg: #f2f4f3;
}
@media (prefers-color-scheme: dark) {
  :root {
    --bg: #111614;
    --panel: #171d1b;
    --ink: #e8efec;
    --muted: #93a29d;
    --line: #263029;
    --accent: #5fd3a3;
    --accent-soft: #17302a;
    --code-bg: #0e1412;
  }
}
* { box-sizing: border-box; }
html { scroll-behavior: smooth; }
body {
  margin: 0;
  background: var(--bg);
  color: var(--ink);
  font-family: "Pretendard", -apple-system, "Segoe UI", "Malgun Gothic", system-ui, sans-serif;
  line-height: 1.7;
}
.topbar {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px clamp(16px, 3vw, 32px);
  border-bottom: 1px solid var(--line);
  background: color-mix(in srgb, var(--bg) 88%, transparent);
  backdrop-filter: blur(10px);
}
.brand { margin: 0; font-size: .95rem; font-weight: 800; letter-spacing: -.01em; }
.brand span { color: var(--muted); font-weight: 600; }
.search {
  flex: 1;
  max-width: 420px;
  margin-left: auto;
  padding: 8px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--panel);
  color: var(--ink);
  font: inherit;
  font-size: .85rem;
}
.search:focus { outline: 2px solid var(--accent); outline-offset: 1px; }
.stamp { color: var(--muted); font-size: .75rem; white-space: nowrap; }
.nav-toggle {
  display: none;
  padding: 8px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--panel);
  color: var(--ink);
  font: inherit;
  font-size: .8rem;
  cursor: pointer;
}

.page {
  display: grid;
  grid-template-columns: 288px minmax(0, 1fr);
  gap: clamp(20px, 3vw, 48px);
  width: min(1440px, calc(100% - 2rem));
  margin: 0 auto;
  padding: 24px 0 96px;
}

.side-nav {
  position: sticky;
  top: 68px;
  align-self: start;
  max-height: calc(100vh - 96px);
  overflow: auto;
  padding: 10px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--panel);
}
.nav-group { margin-bottom: 14px; }
.nav-group-title {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0 8px 6px;
  color: var(--muted);
  font-size: .68rem;
  font-weight: 800;
  letter-spacing: .08em;
  text-transform: uppercase;
}
.nav-count {
  padding: 0 6px;
  border-radius: 99px;
  background: var(--code-bg);
  font-size: .65rem;
  font-weight: 700;
}
.nav-doc, .nav-sub {
  display: block;
  border-radius: 8px;
  color: var(--muted);
  text-decoration: none;
}
.nav-doc {
  padding: 6px 10px;
  font-size: .8rem;
  font-weight: 600;
}
.nav-doc:hover { background: var(--code-bg); color: var(--ink); }
.nav-doc.active { background: var(--accent-soft); color: var(--accent); font-weight: 800; }
.nav-subs { display: none; margin: 2px 0 6px; padding-left: 8px; border-left: 1px solid var(--line); }
.nav-subs.open { display: block; }
.nav-sub { padding: 4px 10px; font-size: .76rem; line-height: 1.35; }
.nav-sub-h3 { padding-left: 22px; font-size: .72rem; }
.nav-sub:hover { color: var(--ink); background: var(--code-bg); }
.nav-sub.active { color: var(--accent); background: var(--accent-soft); font-weight: 700; }
.nav-empty { display: none; padding: 12px 10px; color: var(--muted); font-size: .8rem; }
.side-nav.is-empty .nav-empty { display: block; }

main { min-width: 0; }
.doc {
  padding: clamp(20px, 3vw, 40px);
  border: 1px solid var(--line);
  border-radius: 18px;
  background: var(--panel);
}
.doc[hidden] { display: none; }
.doc-path {
  margin: 0 0 18px;
  color: var(--muted);
  font-family: ui-monospace, "Cascadia Code", Consolas, monospace;
  font-size: .72rem;
}
.doc h1 { margin: 0 0 .6em; font-size: 1.7rem; letter-spacing: -.02em; }
.doc h2 { margin: 2.2em 0 .6em; padding-bottom: .3em; border-bottom: 1px solid var(--line); font-size: 1.2rem; }
.doc h3 { margin: 1.8em 0 .5em; font-size: 1rem; }
.doc h4 { margin: 1.4em 0 .4em; font-size: .9rem; color: var(--muted); }
.doc-h { scroll-margin-top: 78px; }
.doc p, .doc li { font-size: .9rem; }
.doc a { color: var(--accent); }
.doc code {
  padding: .12em .35em;
  border-radius: 5px;
  background: var(--code-bg);
  font-family: ui-monospace, "Cascadia Code", Consolas, monospace;
  font-size: .84em;
}
.doc pre {
  overflow-x: auto;
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--code-bg);
  font-size: .8rem;
  line-height: 1.55;
}
.doc pre code { padding: 0; background: none; }
.doc table { display: block; overflow-x: auto; width: 100%; border-collapse: collapse; font-size: .82rem; }
.doc th, .doc td { padding: 8px 12px; border: 1px solid var(--line); text-align: left; vertical-align: top; }
.doc th { background: var(--code-bg); font-weight: 700; }
.doc blockquote {
  margin: 1em 0;
  padding: .1em 1em;
  border-left: 3px solid var(--accent);
  background: var(--accent-soft);
  border-radius: 0 8px 8px 0;
}
.doc hr { border: none; border-top: 1px solid var(--line); margin: 2em 0; }
.doc img { max-width: 100%; }

@media (max-width: 900px) {
  .page { grid-template-columns: minmax(0, 1fr); }
  .nav-toggle { display: block; }
  .side-nav { display: none; position: static; max-height: none; }
  .side-nav.open { display: block; }
  .search { max-width: none; }
}
`;

const SCRIPT = String.raw`
(function () {
  var nav = document.querySelector(".side-nav");
  var search = document.getElementById("search");
  var toggle = document.querySelector(".nav-toggle");
  var docLinks = [].slice.call(document.querySelectorAll(".nav-doc"));
  var articles = [].slice.call(document.querySelectorAll(".doc"));
  var INDEX = window.__DOC_INDEX__ || {};
  var currentDoc = null;
  var observer = null;

  function showDoc(docId, headingId) {
    var target = document.getElementById(docId);
    if (!target) return false;
    if (currentDoc !== docId) {
      articles.forEach(function (a) { a.hidden = a.dataset.doc !== docId; });
      docLinks.forEach(function (a) { a.classList.toggle("active", a.dataset.doc === docId); });
      document.querySelectorAll(".nav-subs").forEach(function (s) {
        s.classList.toggle("open", s.dataset.subs === docId);
      });
      currentDoc = docId;
      watchHeadings();
      var activeLink = nav.querySelector('.nav-doc[data-doc="' + docId + '"]');
      if (activeLink && activeLink.scrollIntoView) {
        activeLink.scrollIntoView({ block: "nearest" });
      }
    }
    if (headingId) {
      var heading = document.getElementById(headingId);
      if (heading) {
        heading.scrollIntoView({ block: "start" });
        return true;
      }
    }
    window.scrollTo({ top: 0 });
    return true;
  }

  // 스크롤 위치에 따라 사이드바 소제목 하이라이트.
  function watchHeadings() {
    if (observer) observer.disconnect();
    var article = document.getElementById(currentDoc);
    if (!article) return;
    var headings = [].slice.call(article.querySelectorAll(".doc-h"));
    if (!headings.length) return;
    observer = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (!entry.isIntersecting) return;
        var id = entry.target.id;
        nav.querySelectorAll(".nav-sub").forEach(function (link) {
          link.classList.toggle("active", link.dataset.target === id);
        });
      });
    }, { rootMargin: "-70px 0px -75% 0px", threshold: 0 });
    headings.forEach(function (h) { observer.observe(h); });
  }

  function routeFromHash() {
    var hash = decodeURIComponent(location.hash.replace(/^#/, ""));
    if (!hash) { showDoc(docLinks[0].dataset.doc); return; }
    var sep = hash.indexOf("--");
    var docId = sep === -1 ? hash : hash.slice(0, sep);
    if (!showDoc(docId, sep === -1 ? null : hash)) {
      showDoc(docLinks[0].dataset.doc);
    }
  }

  // 검색: 제목 + 소제목 + 본문 앞부분을 훑어 사이드바만 걸러낸다.
  function runSearch(raw) {
    var q = raw.trim().toLowerCase();
    var hits = 0;
    docLinks.forEach(function (link) {
      var id = link.dataset.doc;
      var haystack = INDEX[id] || "";
      var match = !q || haystack.indexOf(q) !== -1;
      link.style.display = match ? "" : "none";
      var subs = nav.querySelector('.nav-subs[data-subs="' + id + '"]');
      if (subs) subs.style.display = match && !q ? "" : "none";
      if (match) hits++;
    });
    nav.querySelectorAll(".nav-group").forEach(function (group) {
      var visible = [].slice.call(group.querySelectorAll(".nav-doc")).some(function (a) {
        return a.style.display !== "none";
      });
      group.style.display = visible ? "" : "none";
    });
    nav.classList.toggle("is-empty", hits === 0);
  }

  nav.addEventListener("click", function (event) {
    var link = event.target.closest("a");
    if (!link) return;
    event.preventDefault();
    var hash = decodeURIComponent(link.getAttribute("href").slice(1));
    if (location.hash === "#" + hash) {
      routeFromHash();
    } else {
      location.hash = hash;
    }
    if (window.matchMedia("(max-width: 900px)").matches) nav.classList.remove("open");
  });

  // 본문 안의 상대 링크(docs/screens/s07.md 같은)도 문서 전환으로 처리.
  document.querySelector("main").addEventListener("click", function (event) {
    var link = event.target.closest("a[href]");
    if (!link) return;
    var href = link.getAttribute("href");
    if (!href || /^(https?:|mailto:)/.test(href)) return;
    var clean = href.split("#")[0].replace(/^\.\//, "").replace(/^\//, "");
    if (!/\.(md|ya?ml)$/i.test(clean)) return;
    var guess = clean
      .replace(/^docs\//, "")
      .replace(/\.(md|ya?ml)$/i, "")
      .replace(/\//g, "-")
      .toLowerCase();
    if (document.getElementById(guess)) {
      event.preventDefault();
      location.hash = guess;
    }
  });

  search.addEventListener("input", function () { runSearch(search.value); });
  search.addEventListener("keydown", function (event) {
    if (event.key === "Escape") { search.value = ""; runSearch(""); search.blur(); }
    if (event.key === "Enter") {
      var first = docLinks.filter(function (a) { return a.style.display !== "none"; })[0];
      if (first) location.hash = first.dataset.doc;
    }
  });
  document.addEventListener("keydown", function (event) {
    if (event.key === "/" && document.activeElement !== search) {
      event.preventDefault();
      search.focus();
      search.select();
    }
  });
  if (toggle) {
    toggle.addEventListener("click", function () { nav.classList.toggle("open"); });
  }
  window.addEventListener("hashchange", routeFromHash);
  routeFromHash();
})();
`;

async function main() {
  const docs = await collect();
  const stamp = new Date().toISOString().slice(0, 16).replace("T", " ");
  const searchIndex = {};
  for (const doc of docs) searchIndex[doc.id] = doc.search;

  const html =
    "<!doctype html>\n" +
    '<html lang="ko">\n<head>\n' +
    '<meta charset="utf-8">\n' +
    '<meta name="viewport" content="width=device-width, initial-scale=1">\n' +
    "<title>PhraseLog 문서 인덱스</title>\n" +
    "<style>" + STYLE + "</style>\n" +
    "</head>\n<body>\n" +
    '<header class="topbar">\n' +
    '<button class="nav-toggle" type="button" aria-label="목차 열기">☰ 목차</button>\n' +
    '<p class="brand">PhraseLog <span>문서 인덱스</span></p>\n' +
    '<input class="search" id="search" type="search" placeholder="검색 (/ 키로 포커스)" aria-label="문서 검색">\n' +
    '<p class="stamp">' + docs.length + "개 · " + stamp + "</p>\n" +
    "</header>\n" +
    '<div class="page">\n' +
    '<nav class="side-nav" aria-label="문서 목차">\n' +
    buildSidebar(docs) +
    '<p class="nav-empty">검색 결과가 없어요.</p>\n' +
    "</nav>\n" +
    "<main>\n" + buildArticles(docs) + "\n</main>\n" +
    "</div>\n" +
    "<script>window.__DOC_INDEX__ = " +
    JSON.stringify(searchIndex).replace(/</g, "\\u003c") +
    ";</script>\n" +
    "<script>" + SCRIPT + "</script>\n" +
    "</body>\n</html>\n";

  await mkdir(OUT_DIR, { recursive: true });
  await writeFile(OUT_FILE, html, "utf8");

  const byGroup = GROUPS.map((g) => {
    const n = docs.filter((d) => d.group === g.id).length;
    return n ? g.label + " " + n : null;
  })
    .filter(Boolean)
    .join(" · ");
  console.log("문서 " + docs.length + "개 (" + byGroup + ")");
  console.log("→ " + path.relative(ROOT, OUT_FILE));
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
