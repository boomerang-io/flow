// A sign-in that verified but cannot finish: the install has no admin yet, so the exchange asked
// for the activation code (423). The id_token and nonce wait here, in a five-minute
// httpOnly cookie the browser cannot read, until the activation screen submits the code with them.
// Plain string work so the root loader can read it beside the other bootstrap cookies.
export const PENDING_ACTIVATION_COOKIE = "flow_auth_activation";

const ATTRIBUTES = "Path=/; HttpOnly; Secure; SameSite=Lax";

/** The Set-Cookie value that holds a verified sign-in until the code arrives. */
export function writePendingActivation(idToken: string, nonce: string): string {
  const value = encodeURIComponent(new URLSearchParams({ idToken, nonce }).toString());
  return `${PENDING_ACTIVATION_COOKIE}=${value}; ${ATTRIBUTES}; Max-Age=300`;
}

/** The Set-Cookie value that ends it. */
export function clearPendingActivation(): string {
  return `${PENDING_ACTIVATION_COOKIE}=; ${ATTRIBUTES}; Expires=Thu, 01 Jan 1970 00:00:00 GMT`;
}

/** The held sign-in on a request, or null. */
export function readPendingActivation(request: Request): { idToken: string; nonce: string } | null {
  for (const part of (request.headers.get("Cookie") ?? "").split(";")) {
    const [name, ...value] = part.trim().split("=");
    if (name !== PENDING_ACTIVATION_COOKIE) continue;
    try {
      const held = new URLSearchParams(decodeURIComponent(value.join("=")));
      const idToken = held.get("idToken");
      const nonce = held.get("nonce");
      return idToken && nonce ? { idToken, nonce } : null;
    } catch {
      return null;
    }
  }
  return null;
}
