import Link from "next/link";

export default function NotFound() {
  return (
    <section className="placeholder" aria-labelledby="not-found-title">
      <div className="hero-panel">
        <p className="eyebrow">404</p>
        <h1 id="not-found-title" className="hero-title">
          Page not found
        </h1>
        <p className="hero-copy">
          This route is not part of the current NextMadi v1 screen map.
        </p>
      </div>
      <Link className="route-card" href="/">
        <strong>Go to landing</strong>
        <span>Return to the scaffolded S01 route.</span>
      </Link>
    </section>
  );
}
