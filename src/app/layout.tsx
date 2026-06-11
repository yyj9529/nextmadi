import type { Metadata, Viewport } from "next";
import Link from "next/link";
import "./globals.css";

export const metadata: Metadata = {
  title: {
    default: "PhraseLog",
    template: "%s | PhraseLog",
  },
  description:
    "AI communication coach for turning hard-to-say moments into reusable English practice.",
  manifest: "/manifest.webmanifest",
  icons: {
    icon: "/icon.svg",
  },
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  themeColor: "#222326",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="ko">
      <body>
        <header className="shell site-header">
          <Link className="brand" href="/">
            PhraseLog
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
      </body>
    </html>
  );
}
