import { RoutePlaceholder } from "@/components/RoutePlaceholder";

type ExpressionDetailPageProps = {
  params: Promise<{
    expression_id: string;
  }>;
};

export default async function ExpressionDetailPage({
  params,
}: ExpressionDetailPageProps) {
  const { expression_id } = await params;

  return (
    <RoutePlaceholder
      screenId="S09"
      title="Expression detail"
      summary={`Expression detail route mounted for id: ${expression_id}.`}
      status="Detail placeholder for E07.3."
      nextRoutes={[
        {
          href: "/practice/example-session",
          label: "Practice placeholder",
          description: "Temporary route shape for S12.",
        },
      ]}
    />
  );
}
