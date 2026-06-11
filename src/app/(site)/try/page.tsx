import { RoutePlaceholder } from "@/components/RoutePlaceholder";

export default function TryPage() {
  return (
    <RoutePlaceholder
      screenId="S02"
      title="Try without login"
      summary="Pre-signup text and voice input starts here before S07 analysis."
      status="Input flow placeholder for E05.4 and E05.5."
      nextRoutes={[
        {
          href: "/save/result/example-analysis",
          label: "Analysis result placeholder",
          description: "Temporary route shape for S07 while backend IDs are pending.",
        },
        {
          href: "/login",
          label: "Login",
          description: "Forced signup path when a visitor saves.",
        },
      ]}
    />
  );
}
