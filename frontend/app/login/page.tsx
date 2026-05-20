import Link from "next/link";
import { GATEWAY_URL } from "@/lib/api";

export const metadata = {
  title: "Logga in — Devroom",
};

export default function LoginPage() {
  const oauth2LoginUrl = `${GATEWAY_URL}/oauth2/authorization/auth-service`;

  return (
    <main className="min-h-screen flex items-center justify-center p-6">
      <div className="w-full max-w-sm space-y-6 rounded-lg border border-border-subtle bg-cream-surface p-8">
        <div className="space-y-2">
          <h1 className="font-serif text-3xl">Devroom</h1>
          <p className="text-sm text-text-muted">
            Chatta med fyra @-mentionable AI-mentorer i ditt teams kanaler.
          </p>
        </div>

        <a
          href={oauth2LoginUrl}
          className="block w-full rounded-md bg-text-primary px-4 py-2.5 text-center text-sm font-medium text-cream transition-opacity hover:opacity-90"
        >
          Logga in
        </a>

        <p className="text-center text-sm text-text-muted">
          Inget konto?{" "}
          <Link href="/signup" className="text-accent underline underline-offset-2">
            Skapa ett
          </Link>
        </p>
      </div>
    </main>
  );
}
