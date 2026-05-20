"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import type { Me } from "@/lib/types";

export default function HomePage() {
  const router = useRouter();

  useEffect(() => {
    api<Me>("/api/me")
      .then(() => router.replace("/channels"))
      .catch((err) => {
        if (err instanceof ApiError && err.status === 401) {
          router.replace("/login");
        }
      });
  }, [router]);

  return (
    <div className="flex min-h-screen items-center justify-center text-sm text-text-muted">
      Laddar…
    </div>
  );
}
