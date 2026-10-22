const UNITS = ["B", "KB", "MB", "GB", "TB", "PB"] as const;

/**
 * Human-readable byte size (binary/1024-based, matching how Kubernetes/the backend quotas already
 * present storage - "Gi"). Used by the Artifacts surfaces (run view + Manage Workspace) for a
 * file's `size` in bytes; the Quotas cards format their own GB values inline since the backend
 * quota fields are already GB-scaled integers, not raw bytes.
 */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) {
    return "--";
  }
  if (bytes === 0) {
    return "0 B";
  }
  const exponent = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), UNITS.length - 1);
  const value = bytes / Math.pow(1024, exponent);
  return `${exponent === 0 ? value : value.toFixed(1)} ${UNITS[exponent]}`;
}
