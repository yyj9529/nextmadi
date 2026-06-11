import { RoutePlaceholder } from "@/components/RoutePlaceholder";

export default function LoginPage() {
  return (
    <RoutePlaceholder
      screenId="S03"
      title="Login"
      summary="Google, Kakao, and email magic-link login will attach here."
      status="Auth UI placeholder for E03.5."
      nextRoutes={[
        {
          href: "/welcome/coach",
          label: "Coach selection",
          description: "New users continue to S03b after authentication.",
        },
        {
          href: "/terms",
          label: "Terms",
          description: "Legal route required before launch.",
        },
        {
          href: "/privacy",
          label: "Privacy",
          description: "Privacy route required before launch.",
        },
      ]}
    />
  );
}
