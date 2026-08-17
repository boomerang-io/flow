//@ts-nocheck
import isUrl from "Utils/isUrl";

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
    (isUrl(value) && !Boolean(propsSyntaxFound)) ||
    isPropertySyntaxValid({ value, customPropertySyntaxPattern, propsSyntaxFound })
  ) {
    return value;
  }
  return false;
}
