// Default "" → relativa same-origin-anrop (frontend + gateway delar ingress-host).
// Override med NEXT_PUBLIC_GATEWAY_URL för split-origin lokal dev (npm run dev mot
// en port-forwardad gateway på :8080).
export const GATEWAY_URL =
  process.env.NEXT_PUBLIC_GATEWAY_URL ?? "";

export class ApiError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message);
    this.name = "ApiError";
  }
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const res = await fetch(`${GATEWAY_URL}${path}`, {
    ...init,
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      ...(init.headers ?? {}),
    },
  });

  if (res.status === 401) {
    // /api/me används för att DETEKTERA inloggad — låt callern hantera 401
    // istället för att redirecta (annars infinite-loop på login-sidan).
    if (typeof window !== "undefined" && !path.startsWith("/api/me")) {
      window.location.href = "/login";
    }
    throw new ApiError(401, "Unauthorized");
  }

  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new ApiError(res.status, `HTTP ${res.status}: ${text}`);
  }

  if (res.status === 204) return undefined as T;

  return res.json();
}
