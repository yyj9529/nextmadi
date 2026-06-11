import { RoutePlaceholder } from "@/components/RoutePlaceholder";

export default function ReviewPage() {
  return (
    <RoutePlaceholder
      screenId="S10"
      title="Review"
      summary="Active recall review queue with hard, good, and easy ratings will live here."
      status="Review placeholder for E08.2."
      nextRoutes={[
        {
          href: "/library",
          label: "Back to library",
          description: "Return to saved expressions.",
        },
      ]}
    />
  );
}
