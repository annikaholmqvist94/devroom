import Link from "next/link";
import type { Channel } from "@/lib/types";

// Hårdkodade demo-kanaler. Message-service har inga FK till channels
// (ADR-0005 ingen FK över DB-gränser), så valfria UUIDs funkar för demon.
const CHANNELS: Channel[] = [
  { id: "33333333-3333-3333-3333-333333333301", name: "general" },
  { id: "33333333-3333-3333-3333-333333333302", name: "frontend" },
  { id: "33333333-3333-3333-3333-333333333303", name: "backend" },
];

export function ChannelList() {
  return (
    <nav className="space-y-1">
      <h2 className="mb-2 text-xs font-medium uppercase tracking-wide text-text-muted">
        Kanaler
      </h2>
      {CHANNELS.map((c) => (
        <Link
          key={c.id}
          href={`/channels/${c.id}`}
          className="block rounded-md px-2 py-1.5 text-sm text-text-emphasis hover:bg-cream"
        >
          <span className="text-text-muted">#</span> {c.name}
        </Link>
      ))}
    </nav>
  );
}
