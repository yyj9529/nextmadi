import Link from "next/link";

type RouteLink = {
  href: string;
  label: string;
  description: string;
};

type RoutePlaceholderProps = {
  screenId: string;
  title: string;
  summary: string;
  status?: string;
  nextRoutes?: RouteLink[];
};

export function RoutePlaceholder({
  screenId,
  title,
  summary,
  status = "Scaffolded route. Screen behavior lands in its feature ticket.",
  nextRoutes = [],
}: RoutePlaceholderProps) {
  return (
    <section className="placeholder" aria-labelledby="screen-title">
      <div className="hero-panel">
        <p className="eyebrow">{screenId}</p>
        <h1 id="screen-title" className="hero-title">
          {title}
        </h1>
        <p className="hero-copy">{summary}</p>
      </div>

      <div className="status-grid" aria-label="Implementation status">
        <article className="status-card">
          <p className="status-label">Route status</p>
          <p className="status-value">{status}</p>
        </article>
        <article className="status-card">
          <p className="status-label">Spec source</p>
          <p className="status-value">docs/screens/{screenId.toLowerCase()}.md</p>
        </article>
      </div>

      {nextRoutes.length > 0 ? (
        <ul className="route-list" aria-label="Related routes">
          {nextRoutes.map((route) => (
            <li key={route.href}>
              <Link className="route-card" href={route.href}>
                <strong>{route.label}</strong>
                <span>{route.description}</span>
              </Link>
            </li>
          ))}
        </ul>
      ) : null}
    </section>
  );
}
