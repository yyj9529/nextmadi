import type { DefaultSession } from "next-auth";

declare module "next-auth" {
  interface Session {
    user: {
      id: string;
      isOnboarded: boolean;
    } & DefaultSession["user"];
  }

  interface User {
    phraselogUserId?: string;
    isOnboarded?: boolean;
  }
}

declare module "next-auth/jwt" {
  interface JWT {
    phraselogUserId?: string;
    isOnboarded?: boolean;
  }
}
