"use client";

import { useState } from "react";
import { api, ApiError } from "@/lib/api";
import type { Message, PostMessagePayload } from "@/lib/types";

export function PostMessageForm({
  channelId,
  onPosted,
}: {
  channelId: string;
  onPosted: () => void;
}) {
  const [body, setBody] = useState("");
  const [posting, setPosting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    const trimmed = body.trim();
    if (!trimmed) return;

    setError(null);
    setPosting(true);
    try {
      const payload: PostMessagePayload = {
        channelId,
        body: trimmed,
        parentMessageId: null,
      };
      await api<Message>("/api/messages", {
        method: "POST",
        body: JSON.stringify(payload),
      });
      setBody("");
      onPosted();
    } catch (err) {
      if (err instanceof ApiError) {
        setError(`Kunde inte posta: ${err.message}`);
      } else {
        setError("Kunde inte posta meddelandet.");
      }
    } finally {
      setPosting(false);
    }
  }

  return (
    <form onSubmit={onSubmit} className="space-y-2">
      <div className="flex gap-2">
        <input
          value={body}
          onChange={(e) => setBody(e.target.value)}
          placeholder="Skriv ett meddelande — testa @code-reviewer ..."
          className="flex-1 rounded-md border border-border-emphasis bg-cream px-3 py-2 text-sm outline-none focus:border-accent"
          disabled={posting}
        />
        <button
          type="submit"
          disabled={posting || !body.trim()}
          className="rounded-md bg-text-primary px-4 py-2 text-sm font-medium text-cream transition-opacity hover:opacity-90 disabled:opacity-50"
        >
          {posting ? "Skickar…" : "Skicka"}
        </button>
      </div>
      {error && (
        <p className="text-xs text-error-accent">{error}</p>
      )}
    </form>
  );
}
