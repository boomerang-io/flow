/*
 * The workspace size travels as a Kubernetes quantity ("2Gi", "500Mi"); the Configure form edits
 * it as whole gigabytes. A bare number is the Gi shorthand the server also accepts.
 */
const GIB = 1024 * 1024 * 1024;

const UNITS: Record<string, number> = {
  Ki: 1024,
  Mi: 1024 * 1024,
  Gi: GIB,
  Ti: GIB * 1024,
  K: 1_000,
  M: 1_000_000,
  G: 1_000_000_000,
  T: 1_000_000_000_000,
};

export function quantityToGb(size: string | number | undefined | null, fallback = 1): number {
  if (size === undefined || size === null || size === "") return fallback;
  if (typeof size === "number") return size;
  const match = /^\s*(\d+(?:\.\d+)?)\s*([KMGT]i?)?\s*$/.exec(size);
  if (!match) return fallback;
  const value = Number(match[1]);
  const unit = match[2];
  if (!unit) return value;
  const gb = (value * UNITS[unit]) / GIB;
  return Math.max(1, Math.round(gb));
}

export function gbToQuantity(gb: number): string {
  return `${gb}Gi`;
}
