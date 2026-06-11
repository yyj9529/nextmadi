import { RoutePlaceholder } from "@/components/RoutePlaceholder";
import { coreRoutes } from "@/lib/routes";

export default function LandingPage() {
  return (
    <RoutePlaceholder
      screenId="S01"
      title="PhraseLog"
      summary="Turn what you could not say today into something you can say next time."
      status="Landing route scaffolded for E05.2."
      nextRoutes={coreRoutes}
    />
  );
}
