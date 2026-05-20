"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { api, ApiError, GATEWAY_URL } from "@/lib/api";
import type { Me } from "@/lib/types";
import { ChannelList } from "@/components/ChannelList";

export default function ChannelsLayout({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const [me, setMe] = useState<Me | null>(null);
  const [checking, setChecking] = useState(true);

  useEffect(() => {
    let cancelled = false;
    api<Me>("/api/me")
      .then((data) => {
        if (cancelled) return;
        setMe(data);
        setChecking(false);
      })
      .catch((err) => {
        if (cancelled) return;
        if (err instanceof ApiError && err.status === 401) {
          router.replace("/login");
        } else {
          setChecking(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [router]);

  if (checking) {
    return (
      <div className="min-h-screen flex items-center justify-center text-sm text-text-muted">
        Laddar…
      </div>
    );
  }

  if (!me) return null;

  return (
    <div className="flex min-h-screen">
      <aside className="flex w-64 flex-col border-r border-border-subtle bg-cream-surface p-4">
        <div className="mb-6">
          <h1 className="font-serif text-xl">Devroom</h1>
          <p className="text-xs text-text-muted">{me.displayName}</p>
        </div>

        <div className="flex-1">
          <ChannelList />
        </div>

        <form action={`${GATEWAY_URL}/logout`} method="POST" className="mt-4">
          <button
            type="submit"
            className="w-full rounded-md border border-border-emphasis bg-cream px-3 py-1.5 text-xs text-text-muted hover:text-text-primary"
          >
            Logga ut
          </button>
        </form>
      </aside>

      <main className="flex-1">{children}</main>
    </div>
  );
}
