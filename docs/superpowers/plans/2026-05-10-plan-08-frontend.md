# Plan 08: Frontend (Next.js)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.
>
> **Pre-step:** Inspektera `~/IdeaProjects/dev-mentor/frontend/` för att identifiera vilka filer/komponenter som ska kopieras (Tailwind-config, globala stilar, primitive-komponenter). Lista exakta filer innan kopiering.
>
> **Revision 2026-05-12 — OAuth2-pivot:** Frontend förändras fundamentalt. Konkret:
>
> - **Ta bort `lib/auth.ts` helt.** Ingen localStorage-hantering. Cookie auto-medskickas av browser.
> - **Login är inte ett formulär.** Det är `<a href="/api/auth/login">Logga in</a>` som länkar till Gateway:s `/oauth2/authorization/auth-service`. Gateway hanterar resten av Authorization Code-flödet via redirect-kedjan.
> - **Signup-page tar bort credentials-formuläret.** Istället `<a href="/signup">Skapa konto</a>` som öppnar Auth Service:s signup-form (proxas via Gateway-route).
> - **`lib/api.ts` förenklas:** alla `fetch`-anrop använder `credentials: 'include'`. **Inga Authorization-headers någonsin.** Cookien räcker. Det räcker med:
>
>   ```ts
>   const GATEWAY = process.env.NEXT_PUBLIC_GATEWAY_URL ?? "http://localhost:8080";
>
>   export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
>       const res = await fetch(`${GATEWAY}${path}`, {
>           ...init,
>           credentials: "include",
>           headers: { "Content-Type": "application/json", ...(init.headers ?? {}) },
>       });
>       if (res.status === 401) {
>           // session expirerad eller saknas — redirecta till login
>           window.location.href = "/api/auth/login";
>           throw new Error("Unauthorized");
>       }
>       if (!res.ok) throw new Error(`HTTP ${res.status}: ${await res.text()}`);
>       return res.json();
>   }
>   ```
>
> - **Auth-state-detection:** vid sidladdning, anropa `GET /api/me` (mot Gateway). Om 200 → user är inloggad, visa channel-listan. Om 401 → visa login-knapp.
> - **All path-routing till protected routes (channels) sker via Next.js:** vid `useEffect` på protected pages, gör en `/api/me`-check. Om 401, redirecta till `/login`-sidan (som har login-knappen).
> - **Logout:** `<a href="/api/auth/logout">Logga ut</a>` som länkar till Gateway:s `/logout`. Gateway rensar session och redirectar till frontend-startsidan.
> - **CORS:** Gateway sätter Access-Control-Allow-Credentials: true för origin `http://localhost:3000`. Konfigurerat i Plan 06 application.yml.

**Goal:** Implementera Next.js 16-frontend som klient mot BFF. Login + signup, kanal-listvy, kanalvy med polling 3s, posta meddelanden med @-mentions, expanderbar trådvy, visuell stil ärvd från Nordic Dev Mentor.

**Architecture:** Next.js 16 App Router. Klient-side routing och state. JWT lagrad i localStorage. Autentiserad fetch-wrapper som lägger till Authorization-header. Polling via `setInterval` med visibility-check. Inga server actions (allt går via BFF).

**Tech Stack:** Next.js 16, React 19, TypeScript, Tailwind 4. Återanvänder från Nordic Dev Mentor: Tailwind config, font-setup, primitive-komponenter (Button, Input, Card).

**Refererar spec:** sektion 10.

**Pre-conditions:** plan 01-07 klara. BFF kör på port 8080.

---

## File Structure

```
frontend/
├── package.json
├── next.config.ts
├── tailwind.config.ts                   # kopierad från Nordic Dev Mentor
├── tsconfig.json
├── postcss.config.mjs
├── public/
├── src/
│   ├── app/
│   │   ├── layout.tsx                  # root-layout (kopierad shell)
│   │   ├── page.tsx                    # redirect till /channels eller /login
│   │   ├── globals.css                 # kopierad från Nordic Dev Mentor
│   │   ├── login/page.tsx
│   │   ├── signup/page.tsx
│   │   └── channels/
│   │       ├── layout.tsx              # sidopanel + main
│   │       ├── page.tsx                # tom: välj en kanal
│   │       └── [channelId]/page.tsx    # kanalvy + polling
│   ├── components/
│   │   ├── ui/
│   │   │   ├── Button.tsx              # kopierad
│   │   │   ├── Input.tsx               # kopierad
│   │   │   └── Card.tsx                # kopierad
│   │   ├── ChannelList.tsx
│   │   ├── MessageFeed.tsx
│   │   ├── MessageItem.tsx
│   │   ├── ThreadPanel.tsx
│   │   ├── MentionBadge.tsx
│   │   └── PostMessageForm.tsx
│   ├── lib/
│   │   ├── api.ts                      # autentiserad fetch-wrapper
│   │   ├── auth.ts                     # JWT-hantering, localStorage
│   │   ├── polling.ts                  # usePolling-hook
│   │   └── types.ts
│   └── middleware.ts                   # redirect till /login om ingen JWT
└── .env.local.example                  # NEXT_PUBLIC_GATEWAY_URL=http://localhost:8080
```

---

## Task 1: Initiera Next.js 16

- [ ] **Step 1: Skapa projektet**

```bash
cd ~/IdeaProjects/devroom
npx create-next-app@latest frontend --typescript --tailwind --app --no-src-dir
```

OBS: vi vill ha `--src-dir` (motsvarande). Justera om CLI-flaggan inte stämmer i 16-versionen.

- [ ] **Step 2: Installera beroenden om något saknas**

```bash
cd frontend
npm install
```

- [ ] **Step 3: Verifiera bygge**

```bash
npm run build
npm run lint
```

- [ ] **Step 4: Skapa `.env.local`**

```
NEXT_PUBLIC_GATEWAY_URL=http://localhost:8080
```

- [ ] **Step 5: Commit**

```bash
git add frontend/
git commit -m "feat(frontend): initialize Next.js 16 with Tailwind"
```

---

## Task 2: Kopiera Tailwind-grund och primitives från Nordic Dev Mentor

- [ ] **Step 1: Inspektera Nordic Dev Mentors frontend**

```bash
ls ~/IdeaProjects/dev-mentor/frontend/
cat ~/IdeaProjects/dev-mentor/frontend/tailwind.config.ts 2>/dev/null || ls ~/IdeaProjects/dev-mentor/frontend/src/
```

Identifiera exakt vilka filer ska kopieras:

- [ ] `tailwind.config.ts` (eller motsvarande)
- [ ] `src/app/globals.css`
- [ ] `src/components/ui/Button.tsx`
- [ ] `src/components/ui/Input.tsx`
- [ ] `src/components/ui/Card.tsx`
- [ ] Eventuella font-setups i `src/app/layout.tsx`

- [ ] **Step 2: Kopiera filerna**

```bash
cp ~/IdeaProjects/dev-mentor/frontend/tailwind.config.ts frontend/
cp ~/IdeaProjects/dev-mentor/frontend/src/app/globals.css frontend/src/app/
mkdir -p frontend/src/components/ui
# Kopiera primitiver — anpassa filsökväg om dev-mentor använder annan struktur
cp ~/IdeaProjects/dev-mentor/frontend/src/components/ui/*.tsx frontend/src/components/ui/ 2>/dev/null || true
```

- [ ] **Step 3: Verifiera bygget fortfarande passerar**

```bash
cd frontend && npm run build
```

- [ ] **Step 4: Commit**

```bash
git add frontend/
git commit -m "feat(frontend): inherit Tailwind config and primitives from Nordic Dev Mentor"
```

---

## Task 3: Auth-bibliotek + types

**Files:**
- Create: `frontend/src/lib/types.ts`
- Create: `frontend/src/lib/auth.ts`
- Create: `frontend/src/lib/api.ts`

- [ ] **Step 1: types.ts**

```ts
export type Channel = {
  id: string;
  name: string;
  teamId: string;
};

export type Mention = {
  userId: string;
  isSystem: boolean;
  personality: string | null;
};

export type Message = {
  id: string;
  channelId: string;
  senderId: string;
  body: string;
  parentMessageId: string | null;
  mentions: Mention[];
  createdAt: string;
};

export type User = {
  userId: string;
  displayName: string;
  teamId: string;
  isSystem: boolean;
  mentorPersonality: string | null;
};

export type AuthResponse = {
  userId: string;
  jwt: string;
};
```

- [ ] **Step 2: auth.ts**

```ts
const TOKEN_KEY = "devroom.jwt";
const USER_KEY = "devroom.userId";

export function saveAuth(jwt: string, userId: string) {
  localStorage.setItem(TOKEN_KEY, jwt);
  localStorage.setItem(USER_KEY, userId);
}

export function getAuth(): { jwt: string; userId: string } | null {
  if (typeof window === "undefined") return null;
  const jwt = localStorage.getItem(TOKEN_KEY);
  const userId = localStorage.getItem(USER_KEY);
  return jwt && userId ? { jwt, userId } : null;
}

export function clearAuth() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
}
```

- [ ] **Step 3: api.ts**

```ts
import { getAuth, clearAuth } from "./auth";

const BFF = process.env.NEXT_PUBLIC_GATEWAY_URL ?? "http://localhost:8080";

export async function api<T>(
  path: string,
  init: RequestInit = {}
): Promise<T> {
  const auth = getAuth();
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...((init.headers as Record<string, string>) ?? {}),
  };
  if (auth) headers["Authorization"] = `Bearer ${auth.jwt}`;

  const res = await fetch(`${BFF}${path}`, { ...init, headers });
  if (res.status === 401) {
    clearAuth();
    if (typeof window !== "undefined") {
      window.location.href = "/login";
    }
    throw new Error("Unauthorized");
  }
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`HTTP ${res.status}: ${text}`);
  }
  return res.json();
}
```

- [ ] **Step 4: Commit**.

---

## Task 4: Login + Signup pages

**Files:**
- Create: `frontend/src/app/login/page.tsx`
- Create: `frontend/src/app/signup/page.tsx`

- [ ] **Step 1: Login**

```tsx
"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { api } from "@/lib/api";
import { saveAuth } from "@/lib/auth";
import type { AuthResponse } from "@/lib/types";

export default function LoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      const res = await api<AuthResponse>("/auth/login", {
        method: "POST",
        body: JSON.stringify({ email, password }),
      });
      saveAuth(res.jwt, res.userId);
      router.push("/channels");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Login failed");
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="min-h-screen flex items-center justify-center p-6">
      <form
        onSubmit={onSubmit}
        className="w-full max-w-sm space-y-4 rounded-lg border p-6"
      >
        <h1 className="text-2xl font-semibold">Logga in</h1>
        <input
          type="email"
          required
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          placeholder="email"
          className="w-full rounded border px-3 py-2"
        />
        <input
          type="password"
          required
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          placeholder="password"
          className="w-full rounded border px-3 py-2"
        />
        {error && <p className="text-sm text-red-600">{error}</p>}
        <button
          type="submit"
          disabled={loading}
          className="w-full rounded bg-black px-4 py-2 text-white disabled:opacity-50"
        >
          {loading ? "Loggar in…" : "Logga in"}
        </button>
        <p className="text-sm text-center">
          Inget konto? <a href="/signup" className="underline">Skapa ett</a>
        </p>
      </form>
    </main>
  );
}
```

- [ ] **Step 2: Signup**

(Samma struktur som login, men anrop mot `/auth/signup`. Efter lyckat signup → redirect till `/channels`.)

- [ ] **Step 3: Verifiera UI lokalt**

```bash
cd frontend && npm run dev
```

Öppna http://localhost:3000/login.

- [ ] **Step 4: Commit**.

---

## Task 5: Middleware för auth-redirect

**Files:**
- Create: `frontend/src/middleware.ts`

```ts
import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

export function middleware(request: NextRequest) {
  // Vi kan inte läsa localStorage från middleware, så vi förlitar oss på
  // klient-side redirect i layout. Denna middleware skyddar bara att
  // direkt navigation till /channels först renderar inget förrän JWT verifierats.
  // För enklare demo: gör inget i middleware, hantera i klient-layout.
  return NextResponse.next();
}

export const config = {
  matcher: [],
};
```

OBS: Eftersom JWT lagras i localStorage (inte cookie) kan middleware inte se den. Hantera redirect i `app/channels/layout.tsx` med en `useEffect`. Acceptabelt för demon — produktionssystem hade använt httpOnly-cookies istället.

Commit.

---

## Task 6: Channels-layout (sidopanel + main)

```tsx
// frontend/src/app/channels/layout.tsx
"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { getAuth } from "@/lib/auth";
import { ChannelList } from "@/components/ChannelList";

export default function ChannelsLayout({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const [authed, setAuthed] = useState(false);

  useEffect(() => {
    if (!getAuth()) {
      router.push("/login");
    } else {
      setAuthed(true);
    }
  }, [router]);

  if (!authed) return null;

  return (
    <div className="flex min-h-screen">
      <aside className="w-64 border-r p-4">
        <ChannelList />
      </aside>
      <main className="flex-1">{children}</main>
    </div>
  );
}
```

För kanal-listan: just nu hårdkodar vi de tre seedade kanalerna (UUID:er från plan 05). En riktig "lista kanaler"-endpoint kan adderas senare.

```tsx
// frontend/src/components/ChannelList.tsx
import Link from "next/link";

const CHANNELS = [
  { id: "33333333-3333-3333-3333-333333333301", name: "general" },
  { id: "33333333-3333-3333-3333-333333333302", name: "frontend" },
  { id: "33333333-3333-3333-3333-333333333303", name: "backend" },
];

export function ChannelList() {
  return (
    <nav className="space-y-1">
      <h2 className="text-sm font-semibold opacity-60">Kanaler</h2>
      {CHANNELS.map((c) => (
        <Link
          key={c.id}
          href={`/channels/${c.id}`}
          className="block rounded px-2 py-1 hover:bg-gray-100"
        >
          # {c.name}
        </Link>
      ))}
    </nav>
  );
}
```

Commit.

---

## Task 7: Polling-hook + MessageFeed

```ts
// frontend/src/lib/polling.ts
import { useEffect, useRef, useState } from "react";

export function usePolling<T>(
  fetchFn: () => Promise<T>,
  intervalMs: number,
  enabled = true
) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [loading, setLoading] = useState(false);
  const timeoutRef = useRef<NodeJS.Timeout | null>(null);

  useEffect(() => {
    if (!enabled) return;

    let cancelled = false;
    let backoff = 0;

    async function run() {
      if (cancelled) return;
      setLoading(true);
      try {
        const res = await fetchFn();
        if (cancelled) return;
        setData(res);
        setError(null);
        backoff = 0;
      } catch (err) {
        if (cancelled) return;
        setError(err instanceof Error ? err : new Error(String(err)));
        backoff = Math.min((backoff || 1000) * 2, 30000);
      } finally {
        setLoading(false);
        if (!cancelled) {
          const next = backoff || intervalMs;
          timeoutRef.current = setTimeout(run, next);
        }
      }
    }

    function onVisibilityChange() {
      if (document.visibilityState === "visible") {
        if (timeoutRef.current) clearTimeout(timeoutRef.current);
        run();
      }
    }

    run();
    document.addEventListener("visibilitychange", onVisibilityChange);

    return () => {
      cancelled = true;
      if (timeoutRef.current) clearTimeout(timeoutRef.current);
      document.removeEventListener("visibilitychange", onVisibilityChange);
    };
  }, [fetchFn, intervalMs, enabled]);

  return { data, error, loading };
}
```

```tsx
// frontend/src/components/MessageFeed.tsx
"use client";

import { useCallback, useState } from "react";
import { api } from "@/lib/api";
import { usePolling } from "@/lib/polling";
import type { Message } from "@/lib/types";
import { MessageItem } from "./MessageItem";
import { PostMessageForm } from "./PostMessageForm";

export function MessageFeed({ channelId }: { channelId: string }) {
  const [refreshKey, setRefreshKey] = useState(0);

  const fetcher = useCallback(
    () => api<Message[]>(`/messages?channelId=${channelId}`),
    [channelId, refreshKey]
  );

  const { data: messages = [], error } = usePolling(fetcher, 3000);

  const topLevel = (messages ?? []).filter((m) => m.parentMessageId === null);

  return (
    <div className="flex flex-col h-screen">
      <div className="flex-1 overflow-y-auto p-4 space-y-2">
        {error && <p className="text-sm text-red-600">Fel: {error.message}</p>}
        {topLevel.map((m) => (
          <MessageItem
            key={m.id}
            message={m}
            replies={(messages ?? []).filter((c) => c.parentMessageId === m.id)}
          />
        ))}
      </div>
      <div className="border-t p-4">
        <PostMessageForm
          channelId={channelId}
          onPosted={() => setRefreshKey((k) => k + 1)}
        />
      </div>
    </div>
  );
}
```

Commit.

---

## Task 8: MessageItem + ThreadPanel + MentionBadge + PostMessageForm

```tsx
// MentionBadge.tsx
export function MentionBadge({ name, isSystem }: { name: string; isSystem: boolean }) {
  return (
    <span
      className={
        isSystem
          ? "rounded bg-blue-100 px-1.5 py-0.5 text-xs font-mono text-blue-800"
          : "rounded bg-gray-100 px-1.5 py-0.5 text-xs font-mono"
      }
    >
      @{name}
    </span>
  );
}
```

```tsx
// MessageItem.tsx
import { useState } from "react";
import type { Message } from "@/lib/types";

export function MessageItem({ message, replies }: { message: Message; replies: Message[] }) {
  const [expanded, setExpanded] = useState(false);
  return (
    <div className="rounded border p-3">
      <div className="flex justify-between items-baseline">
        <span className="font-mono text-sm opacity-60">{message.senderId.slice(0, 8)}</span>
        <span className="text-xs opacity-50">{new Date(message.createdAt).toLocaleTimeString()}</span>
      </div>
      <p className="mt-1 whitespace-pre-wrap">{renderBody(message.body)}</p>
      {replies.length > 0 && (
        <button
          onClick={() => setExpanded((v) => !v)}
          className="mt-2 text-sm underline opacity-70"
        >
          {expanded ? "Dölj" : `Visa ${replies.length} svar`}
        </button>
      )}
      {expanded && (
        <div className="mt-2 ml-4 border-l-2 pl-3 space-y-2">
          {replies.map((r) => (
            <div key={r.id} className="rounded bg-gray-50 p-2">
              <span className="font-mono text-xs opacity-60">{r.senderId.slice(0, 8)}</span>
              <p className="whitespace-pre-wrap">{r.body}</p>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function renderBody(body: string): React.ReactNode {
  return body.split(/(@[a-z0-9-]+)/g).map((part, i) =>
    part.startsWith("@") ? (
      <span key={i} className="font-mono text-blue-600">{part}</span>
    ) : (
      part
    )
  );
}
```

```tsx
// PostMessageForm.tsx
"use client";

import { useState } from "react";
import { api } from "@/lib/api";

export function PostMessageForm({ channelId, onPosted }: { channelId: string; onPosted: () => void }) {
  const [body, setBody] = useState("");
  const [posting, setPosting] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!body.trim()) return;
    setPosting(true);
    try {
      await api("/messages", {
        method: "POST",
        body: JSON.stringify({ channelId, body }),
      });
      setBody("");
      onPosted();
    } finally {
      setPosting(false);
    }
  }

  return (
    <form onSubmit={onSubmit} className="flex gap-2">
      <input
        value={body}
        onChange={(e) => setBody(e.target.value)}
        placeholder="Skriv ett meddelande, t.ex. @code-reviewer kan du kolla det här"
        className="flex-1 rounded border px-3 py-2"
      />
      <button
        type="submit"
        disabled={posting || !body.trim()}
        className="rounded bg-black px-4 py-2 text-white disabled:opacity-50"
      >
        Skicka
      </button>
    </form>
  );
}
```

Commit.

---

## Task 9: Channel-page + entry-page

```tsx
// frontend/src/app/channels/[channelId]/page.tsx
import { MessageFeed } from "@/components/MessageFeed";

export default async function ChannelPage({ params }: { params: Promise<{ channelId: string }> }) {
  const { channelId } = await params;
  return <MessageFeed channelId={channelId} />;
}
```

```tsx
// frontend/src/app/page.tsx
"use client";
import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { getAuth } from "@/lib/auth";

export default function HomePage() {
  const router = useRouter();
  useEffect(() => {
    router.push(getAuth() ? "/channels" : "/login");
  }, [router]);
  return null;
}
```

Commit.

---

## Task 10: Smoke-test i webbläsare

```bash
# Starta hela backend-stacken (plan 06 task 9-style)
docker compose -f docker-compose.dev.yml up -d
mvn -pl services/auth-service spring-boot:run &
mvn -pl services/user-service spring-boot:run &
mvn -pl services/message-service spring-boot:run &
mvn -pl services/gateway spring-boot:run &
mvn -pl services/bot-service spring-boot:run &
sleep 30

# Starta frontend
cd frontend && npm run dev
```

Öppna http://localhost:3000:

1. Signa upp med email + lösenord → automatisk redirect till `/channels`
2. Klicka på `#general` → kanalvy laddas via polling
3. Skriv `Hej @code-reviewer kan du förklara dependency injection?` → klicka Skicka
4. Vänta ~5-8 sekunder → bot-svar dyker upp i en tråd under ditt meddelande
5. Klicka "Visa 1 svar" → bot-svaret expanderas

---

## Task 11: Plan-slut

- [ ] `npm run build` passerar utan fel
- [ ] `npm run lint` passerar
- [ ] Hela demo-flödet (signup → mention → bot-svar) fungerar i webbläsaren
- [ ] Stilen är estetiskt konsistent med Nordic Dev Mentor

---

## Plan 8 — slut

Vid godkänd verifikation: gå vidare till plan 09 (cross-service integrationstester).
