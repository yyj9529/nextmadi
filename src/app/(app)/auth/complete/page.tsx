import { redirect } from "next/navigation";

import { auth } from "@/auth";
import { getPostLoginRedirectPath } from "@/lib/auth/oauth-flow";

export default async function AuthCompletePage() {
  const session = await auth();

  if (!session?.user?.id) {
    redirect("/login?callback_error=oauth");
  }

  redirect(getPostLoginRedirectPath(session.user.isOnboarded));
}
