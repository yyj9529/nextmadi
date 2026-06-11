import { RoutePlaceholder } from "@/components/RoutePlaceholder";

export default function SettingsPage() {
  return (
    <RoutePlaceholder
      screenId="S11"
      title="Settings"
      summary="Profile, coach choice, usage, logout, and account deletion controls live here."
      status="Settings placeholder for E09.5."
      nextRoutes={[
        {
          href: "/welcome/coach",
          label: "Coach selection",
          description: "Shared coach-card layout for changing coach.",
        },
      ]}
    />
  );
}
