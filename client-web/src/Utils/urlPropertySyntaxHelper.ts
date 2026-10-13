//@ts-nocheck

/**
 * A plain, fully-qualified web URL - the WHATWG `URL` parser decides, so there is no regex to keep
 * in step with it. The protocol check is what keeps the parser honest here: `URL` happily accepts
 * `mailto:` or `javascript:`, and a workflow parameter of type URL is always an http(s) address.
 */
export function isHttpUrl(value: string): boolean {
  if (typeof value !== "string") return false;
  try {
    const { protocol } = new URL(value);
    return protocol === "http:" || protocol === "https:";
  } catch {
    return false;
  }
}

const defaultCustomPropertySyntaxPattern = /\$\{p:([a-zA-Z0-9_.-]+)\}|\$\(([a-zA-Z0-9_.-\s]+)\)/g;
const defaultCustomPropertyStartsWithPattern = /\$\{|\$\(/g;

export function isPropertySyntaxValid({ value, customPropertySyntaxPattern, propsSyntaxFound }) {
  // Look property pattern and capture group for the property itself
  let match = value?.match(customPropertySyntaxPattern);
  // if the first matched group is truthy, then a property has been entered
  // Empty properties are not valid
  if (Array.isArray(match) && match.length === propsSyntaxFound) {
    return true;
  } else {
    return false;
  }
}

export function validateUrlWithProperties({
  customPropertySyntaxPattern = defaultCustomPropertySyntaxPattern,
  customPropertyStartsWithPattern = defaultCustomPropertyStartsWithPattern,
  value,
}) {
  if (!Boolean(value)) return true;
  const propsSyntaxFound = value.match(customPropertyStartsWithPattern)?.length ?? 0;
  if (
    (isHttpUrl(value) && !Boolean(propsSyntaxFound)) ||
    isPropertySyntaxValid({ value, customPropertySyntaxPattern, propsSyntaxFound })
  ) {
    return value;
  }
  return false;
}
