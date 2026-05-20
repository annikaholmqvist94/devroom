type Variant = "system" | "human";

export function MentionBadge({
  label,
  variant,
}: {
  label: string;
  variant: Variant;
}) {
  const base =
    "inline-flex items-center rounded px-1.5 py-0.5 font-mono text-[0.85em] leading-none";
  const styles =
    variant === "system"
      ? "bg-cream-surface text-accent border border-border-subtle"
      : "bg-cream-surface text-text-emphasis border border-border-subtle";
  return <span className={`${base} ${styles}`}>@{label}</span>;
}
