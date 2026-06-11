import { RoutePlaceholder } from "@/components/RoutePlaceholder";

export default function CoachSelectionPage() {
  return (
    <RoutePlaceholder
      screenId="S03b"
      title="Choose your coach"
      summary="Mia, David, and Sarah coach cards will complete onboarding here."
      status="Coach selection placeholder for E09.2."
      nextRoutes={[
        {
          href: "/home",
          label: "Home",
          description: "Onboarded users land on S04.",
        },
      ]}
    />
  );
}
