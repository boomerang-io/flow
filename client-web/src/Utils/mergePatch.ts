import { REDACTED } from "Constants";

/** The media type of a JSON Merge Patch (RFC 7396). */
export const MERGE_PATCH_JSON = "application/merge-patch+json";

// Blank, null and absent are the same "no value" when comparing what a form holds with what was
// read, so an untouched empty field is not sent as a change.
const normalise = (value: unknown) => (value === undefined || value === null || value === "" ? null : value);

/** Whether a form value differs from the value that was read, treating blank, null and absent as one. */
export function hasChanged(original: unknown, edited: unknown) {
  return JSON.stringify(normalise(edited)) !== JSON.stringify(normalise(original));
}

/**
 * The JSON Merge Patch for an edit: only the members that differ from what was read. An untouched
 * secret comes back from a form as the redaction marker and is left out, so the stored value is
 * kept; Clear sends "" and is included. Members named in `skip` (an id, or the name that addresses
 * the resource) are never patched.
 */
export function toMergePatch(original: object, edited: object, skip: string[] = ["id", "name"]) {
  const read = original as Record<string, unknown>;
  const patch: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(edited)) {
    if (skip.includes(key) || value === REDACTED) {
      continue;
    }
    if (hasChanged(read[key], value)) {
      patch[key] = value;
    }
  }
  return patch;
}
