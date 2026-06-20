"use client";

import { signIn, signOut } from "next-auth/react";

export {
  AUTH_COMPLETE_REDIRECT,
  getOAuthCallbackErrorMessage,
  getPostLoginRedirectPath,
} from "./oauth-flow";
import {
  AUTH_COMPLETE_REDIRECT,
  type OAuthProvider,
} from "./oauth-flow";

export async function signInWithOAuthProvider(provider: OAuthProvider) {
  await signIn(provider, { redirectTo: AUTH_COMPLETE_REDIRECT });
}

export async function signOutToLanding() {
  await signOut({ redirectTo: "/" });
}
