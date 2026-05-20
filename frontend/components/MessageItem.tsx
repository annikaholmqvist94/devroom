"use client";

import { useState } from "react";
import type { Message, Mention } from "@/lib/types";
import { MentionBadge } from "./MentionBadge";

export function MessageItem({
  message,
  replies,
}: {
  message: Message;
  replies: Message[];
}) {
  const [expanded, setExpanded] = useState(false);
  const time = new Date(message.createdAt).toLocaleTimeString([], {
    hour: "2-digit",
    minute: "2-digit",
  });

  return (
    <article className="rounded-md border border-border-subtle bg-cream p-3">
      <header className="flex items-baseline justify-between">
        <span className="font-mono text-xs text-text-muted">
          {shortId(message.senderId)}
        </span>
        <span className="text-xs text-text-disabled">{time}</span>
      </header>

      <div className="mt-1 whitespace-pre-wrap text-sm leading-relaxed text-text-primary">
        {renderBody(message.body, message.mentions)}
      </div>

      {replies.length > 0 && (
        <button
          type="button"
          onClick={() => setExpanded((v) => !v)}
          className="mt-2 text-xs text-text-muted underline underline-offset-2 hover:text-accent"
        >
          {expanded ? "Dölj svar" : `Visa ${replies.length} svar`}
        </button>
      )}

      {expanded && replies.length > 0 && (
        <div className="mt-2 space-y-2 border-l-2 border-border-emphasis pl-3">
          {replies.map((r) => (
            <div key={r.id} className="rounded bg-cream-surface p-2">
              <div className="flex items-baseline justify-between">
                <span className="font-mono text-xs text-text-muted">
                  {shortId(r.senderId)}
                </span>
                <span className="text-xs text-text-disabled">
                  {new Date(r.createdAt).toLocaleTimeString([], {
                    hour: "2-digit",
                    minute: "2-digit",
                  })}
                </span>
              </div>
              <div className="mt-1 whitespace-pre-wrap text-sm text-text-primary">
                {renderBody(r.body, r.mentions)}
              </div>
            </div>
          ))}
        </div>
      )}
    </article>
  );
}

function shortId(id: string): string {
  return id.slice(0, 8);
}

/**
 * Splittrar body på @-mentions och renderar resolved ones som MentionBadge.
 * Mentions resolveras server-side i Message Service via regex @([a-z0-9-]+),
 * så vi matchar med samma regex här för konsistens.
 */
function renderBody(body: string, mentions: Mention[]): React.ReactNode {
  const parts = body.split(/(@[a-z0-9-]+)/g);
  return parts.map((part, i) => {
    if (!part.startsWith("@")) return <span key={i}>{part}</span>;
    const handle = part.slice(1);
    const m = mentions.find(
      (mention) =>
        mention.personality === handle ||
        mention.userId === handle,
    );
    if (m) {
      const label = m.isSystem && m.personality ? m.personality : handle;
      return (
        <MentionBadge key={i} label={label} variant={m.isSystem ? "system" : "human"} />
      );
    }
    return (
      <span key={i} className="text-text-muted">
        {part}
      </span>
    );
  });
}
