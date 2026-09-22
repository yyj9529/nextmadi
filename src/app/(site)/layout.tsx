import Link from "next/link";

export default function SiteLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <>
      <header className="shell site-header">
        <Link className="brand" href="/">
          NextMadi
        </Link>
        <nav className="nav-links" aria-label="Primary">
          <Link className="nav-link" href="/try">
            Try
          </Link>
          <Link className="nav-link" href="/home">
            Home
          </Link>
          <Link className="nav-link" href="/library">
            Library
          </Link>
          <Link className="nav-link" href="/review">
            Review
          </Link>
        </nav>
      </header>
      <main className="shell main">{children}</main>
    </>
  );
}
