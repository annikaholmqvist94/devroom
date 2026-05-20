"use client";

import { useCallback, useMemo } from "react";
import { api } from "@/lib/api";
import { usePolling } from "@/lib/polling";
import type { Message } from "@/lib/types";
import { MessageItem } from "./MessageItem";
import { PostMessageForm } from "./PostMessageForm";

export function MessageFeed({ channelId }: { channelId: string }) {
  const fetcher = useCallback(
    () => api<Message[]>(`/api/messages?channelId=${channelId}`),
    [channelId],
  );

  const { data, error, refetch } = usePolling(fetcher, 3000);

  const { topLevel, repliesByParent } = useMemo(() => {
    const messages = data ?? [];
    const top = messages.filter((m) => m.parentMessageId === null);
    const repliesMap = new Map<string, Message[]>();
    for (const m of messages) {
      if (m.parentMessageId !== null) {
        const list = repliesMap.get(m.parentMessageId) ?? [];
        list.push(m);
        repliesMap.set(m.parentMessageId, list);
      }
    }
    return { topLevel: top, repliesByParent: repliesMap };
  }, [data]);

  return (
    <div className="flex h-screen flex-col">
      <div className="flex-1 space-y-3 overflow-y-auto p-6">
        {error && (
          <div className="rounded-md border border-error-border bg-error-bg px-3 py-2 text-sm text-error-accent">
            Kunde inte hämta meddelanden: {error.message}
          </div>
        )}
        {data && topLevel.length === 0 && (
          <p className="py-12 text-center text-sm text-text-muted">
            Inga meddelanden än. Skriv det första — testa{" "}
            <code className="font-mono text-text-emphasis">
              @code-reviewer kan du förklara dependency injection?
            </code>
          </p>
        )}
        {topLevel.map((m) => (
          <MessageItem
            key={m.id}
            message={m}
            replies={repliesByParent.get(m.id) ?? []}
          />
        ))}
      </div>

      <div className="border-t border-border-subtle bg-cream-surface p-4">
        <PostMessageForm channelId={channelId} onPosted={refetch} />
      </div>
    </div>
  );
}
