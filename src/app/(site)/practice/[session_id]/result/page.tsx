import { RoutePlaceholder } from "@/components/RoutePlaceholder";

type PracticeResultPageProps = {
  params: Promise<{
    session_id: string;
  }>;
};

export default async function PracticeResultPage({
  params,
}: PracticeResultPageProps) {
  const { session_id } = await params;

  return (
    <RoutePlaceholder
      screenId="S12b"
      title="Roleplay result"
      summary={`Roleplay result route mounted for session: ${session_id}.`}
      status="Result placeholder for E10.6."
      nextRoutes={[
        {
          href: "/library",
          label: "Library",
          description: "Saved recommendations should appear in S08.",
        },
      ]}
    />
  );
}
