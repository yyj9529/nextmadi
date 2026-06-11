import { RoutePlaceholder } from "@/components/RoutePlaceholder";

type PracticePageProps = {
  params: Promise<{
    session_id: string;
  }>;
};

export default async function PracticePage({ params }: PracticePageProps) {
  const { session_id } = await params;

  return (
    <RoutePlaceholder
      screenId="S12"
      title="Guided roleplay"
      summary={`Roleplay session route mounted for id: ${session_id}.`}
      status="Practice placeholder for E10.4."
      nextRoutes={[
        {
          href: `/practice/${session_id}/result`,
          label: "Session result",
          description: "Temporary route shape for S12b.",
        },
      ]}
    />
  );
}
