"use client";

import { useCallback, useEffect, useRef, useState } from "react";

type PollingState<T> = {
  data: T | null;
  error: Error | null;
  loading: boolean;
  refetch: () => void;
};

/**
 * usePolling — pollar fetchFn på intervall, pausar när fliken är dold,
 * gör exponential backoff vid errors, exponerar refetch().
 *
 * Notera att fetchFn lagras i en ref så att hooken inte startar om varje
 * gång callern råkar skapa en ny funktionsreferens (vanlig footgun).
 */
export function usePolling<T>(
  fetchFn: () => Promise<T>,
  intervalMs: number,
  enabled = true,
): PollingState<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [loading, setLoading] = useState(false);

  const fetchFnRef = useRef(fetchFn);
  useEffect(() => {
    fetchFnRef.current = fetchFn;
  }, [fetchFn]);

  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const cancelledRef = useRef(false);
  const backoffRef = useRef(0);

  const run = useCallback(async () => {
    if (cancelledRef.current) return;
    setLoading(true);
    try {
      const res = await fetchFnRef.current();
      if (cancelledRef.current) return;
      setData(res);
      setError(null);
      backoffRef.current = 0;
    } catch (err) {
      if (cancelledRef.current) return;
      setError(err instanceof Error ? err : new Error(String(err)));
      backoffRef.current = Math.min((backoffRef.current || 1000) * 2, 30_000);
    } finally {
      if (!cancelledRef.current) {
        setLoading(false);
        const next = backoffRef.current || intervalMs;
        timeoutRef.current = setTimeout(run, next);
      }
    }
  }, [intervalMs]);

  const refetch = useCallback(() => {
    if (timeoutRef.current) clearTimeout(timeoutRef.current);
    run();
  }, [run]);

  useEffect(() => {
    if (!enabled) return;
    cancelledRef.current = false;
    backoffRef.current = 0;

    function onVisibilityChange() {
      if (document.visibilityState === "visible") {
        if (timeoutRef.current) clearTimeout(timeoutRef.current);
        run();
      } else if (timeoutRef.current) {
        clearTimeout(timeoutRef.current);
        timeoutRef.current = null;
      }
    }

    run();
    document.addEventListener("visibilitychange", onVisibilityChange);

    return () => {
      cancelledRef.current = true;
      if (timeoutRef.current) clearTimeout(timeoutRef.current);
      document.removeEventListener("visibilitychange", onVisibilityChange);
    };
  }, [enabled, run]);

  return { data, error, loading, refetch };
}
