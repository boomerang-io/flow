// A workspace's name is its key in URLs and tokens. The server accepts it only in one form, so the
// webapp derives it by the server's own rule (service-core StringUtil.kebabCase): letters a-z and
// digits kept, spaces and apostrophes to hyphens, runs of hyphens to one, lower-cased. Anything
// else, accented or non-Latin letters included, is dropped.
export function toWorkspaceName(value: string): string {
  return value
    .replace(/[^A-Za-z0-9' -]/g, "")
    .replace(/\s+/g, "-")
    .replace(/'/g, "-")
    .replace(/-+/g, "-")
    .toLowerCase();
}

// A name shaped like a workspace id would make a path naming it ambiguous: the server refuses it.
export function isIdShapedName(name: string): boolean {
  return /^[0-9a-f]{24}$/i.test(name);
}

/** Why a display name yields no workspace name the server takes, or null when it yields one. */
export function workspaceNameProblem(value: string | undefined): string | null {
  const name = toWorkspaceName(value ?? "");
  if (!name || !/[a-z0-9]/.test(name)) {
    return "Crikey, that leaves nothing for the web address. Pop in a few letters or numbers (a to z, 0 to 9).";
  }
  if (isIdShapedName(name)) {
    return "That one looks like a workspace id, so it can't be a name. Add a word or two.";
  }
  return null;
}
