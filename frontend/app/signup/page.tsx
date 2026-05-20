"use client";

import { useState } from "react";
import Link from "next/link";
import { api, ApiError, GATEWAY_URL } from "@/lib/api";

type SignupResponse = { userId: string };

export default function SignupPage() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);

    if (password.length < 8) {
      setError("Lösenordet måste vara minst 8 tecken.");
      return;
    }

    setSubmitting(true);
    try {
      await api<SignupResponse>("/signup", {
        method: "POST",
        body: JSON.stringify({ email, password }),
      });
      // Signup lyckades — kicka in i OAuth2-login så användaren får en
      // riktig session-cookie. Hen får ange samma credentials igen
      // (Auth Service:s login-form), vilket är acceptabelt för demon.
      window.location.href = `${GATEWAY_URL}/oauth2/authorization/auth-service`;
    } catch (err) {
      if (err instanceof ApiError && err.status === 400) {
        setError("Ogiltig email eller lösenord. Kontrollera och försök igen.");
      } else if (err instanceof ApiError && err.status === 409) {
        setError("En användare med den här email-adressen finns redan.");
      } else {
        setError(err instanceof Error ? err.message : "Något gick fel.");
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="min-h-screen flex items-center justify-center p-6">
      <form
        onSubmit={onSubmit}
        className="w-full max-w-sm space-y-5 rounded-lg border border-border-subtle bg-cream-surface p-8"
      >
        <div className="space-y-2">
          <h1 className="font-serif text-3xl">Skapa konto</h1>
          <p className="text-sm text-text-muted">
            Devroom — chatta med @-mentionable AI-mentorer.
          </p>
        </div>

        <div className="space-y-3">
          <label className="block space-y-1">
            <span className="text-xs font-medium text-text-emphasis">Email</span>
            <input
              type="email"
              required
              autoComplete="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="w-full rounded-md border border-border-emphasis bg-cream px-3 py-2 text-sm outline-none focus:border-accent"
            />
          </label>

          <label className="block space-y-1">
            <span className="text-xs font-medium text-text-emphasis">Lösenord</span>
            <input
              type="password"
              required
              minLength={8}
              autoComplete="new-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="w-full rounded-md border border-border-emphasis bg-cream px-3 py-2 text-sm outline-none focus:border-accent"
            />
            <span className="text-xs text-text-muted">Minst 8 tecken.</span>
          </label>
        </div>

        {error && (
          <div className="rounded-md border border-error-border bg-error-bg px-3 py-2 text-sm text-error-accent">
            {error}
          </div>
        )}

        <button
          type="submit"
          disabled={submitting}
          className="w-full rounded-md bg-text-primary px-4 py-2.5 text-sm font-medium text-cream transition-opacity hover:opacity-90 disabled:opacity-50"
        >
          {submitting ? "Skapar konto…" : "Skapa konto"}
        </button>

        <p className="text-center text-sm text-text-muted">
          Har redan ett konto?{" "}
          <Link href="/login" className="text-accent underline underline-offset-2">
            Logga in
          </Link>
        </p>
      </form>
    </main>
  );
}
