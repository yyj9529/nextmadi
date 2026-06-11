import { RoutePlaceholder } from "@/components/RoutePlaceholder";

export default function HomePage() {
  return (
    <RoutePlaceholder
      screenId="S04"
      title="Home"
      summary="Coach greeting, mic entry, recent expressions, and review/roleplay navigation live here."
      status="Dashboard placeholder for E09.4."
      nextRoutes={[
        {
          href: "/library",
          label: "Library",
          description: "Saved expressions list.",
        },
        {
          href: "/review",
          label: "Review",
          description: "Due review cards.",
        },
        {
          href: "/settings",
          label: "Settings",
          description: "Account and coach preferences.",
        },
      ]}
    />
  );
}
