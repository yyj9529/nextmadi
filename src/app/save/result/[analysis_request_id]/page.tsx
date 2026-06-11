import { RoutePlaceholder } from "@/components/RoutePlaceholder";

type AnalysisResultPageProps = {
  params: Promise<{
    analysis_request_id: string;
  }>;
};

export default async function AnalysisResultPage({
  params,
}: AnalysisResultPageProps) {
  const { analysis_request_id } = await params;

  return (
    <RoutePlaceholder
      screenId="S07"
      title="Analysis result"
      summary={`Analysis result route mounted for id: ${analysis_request_id}.`}
      status="S07 placeholder for E06.2."
      nextRoutes={[
        {
          href: "/login",
          label: "Save flow",
          description: "Pre-signup saves route through S03 and pending_save.",
        },
        {
          href: "/library",
          label: "Library",
          description: "Saved expressions land in S08.",
        },
      ]}
    />
  );
}
