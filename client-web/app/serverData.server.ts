/*
 * The runtime values the browser bundle reads from `window._SERVER_DATA` (declared in
 * src/Types/index.tsx; read at module load by Config/appConfig.ts and Config/servicesConfig.ts).
 * They come straight from this process's environment, so every value is set per deployment
 * (docker-compose.yml, a Kubernetes Deployment) without a rebuild. Only keys present in the
 * environment are forwarded: an unset key falls through to the browser bundle's own default
 * instead of being clobbered with an empty string.
 *
 * The root loader (app/root.tsx) returns this and the document shell writes it into an inline
 * script ahead of the module scripts. The `.server` suffix keeps the process.env read out of the
 * browser bundle.
 */
const SERVER_DATA_KEYS = [
  "APP_ROOT",
  "CORE_ENV_URL",
  "EMBEDDED_MODE",
  "PRODUCT_ENV_URL",
  "PRODUCT_SERVICE_ENV_URL",
  "PRODUCT_STANDALONE",
] as const;

export type ServerData = Partial<Record<(typeof SERVER_DATA_KEYS)[number], string>>;

export function readServerData(): ServerData {
  const data: ServerData = {};
  for (const key of SERVER_DATA_KEYS) {
    const value = process.env[key];
    if (value !== undefined) {
      data[key] = value;
    }
  }
  return data;
}
