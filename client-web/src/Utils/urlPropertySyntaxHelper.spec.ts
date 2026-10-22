import { describe, expect, it } from "vitest";
import { isHttpUrl, validateUrlWithProperties } from "./urlPropertySyntaxHelper";

// A workflow parameter of type URL is valid when it is either a plain http(s) URL or entirely
// made of ${p:...} / $(...) property references - the field is submitted before those references
// resolve, so a half-resolved value can never be parsed as a URL.

describe("isHttpUrl", () => {
  it.each(["http://localhost:8080", "https://useboomerang.io/docs?a=1#b", "http://10.0.0.1/path"])(
    "accepts %s",
    (value) => {
      expect(isHttpUrl(value)).toBe(true);
    },
  );

  it.each([
    "useboomerang.io",
    "//useboomerang.io",
    "mailto:someone@useboomerang.io",
    "javascript:alert(1)",
    "ftp://files.useboomerang.io",
    "",
  ])("rejects %s", (value) => {
    expect(isHttpUrl(value)).toBe(false);
  });

  it("rejects a non-string", () => {
    expect(isHttpUrl(undefined as unknown as string)).toBe(false);
  });
});

describe("validateUrlWithProperties", () => {
  it("passes an empty value through to the required() check", () => {
    expect(validateUrlWithProperties({ value: "" })).toBe(true);
  });

  it("accepts a plain http(s) URL", () => {
    expect(validateUrlWithProperties({ value: "https://useboomerang.io" })).toBe("https://useboomerang.io");
  });

  it("accepts a value made entirely of property references", () => {
    expect(validateUrlWithProperties({ value: "${p:host}" })).toBe("${p:host}");
    expect(validateUrlWithProperties({ value: "${p:scheme}${p:host}" })).toBe("${p:scheme}${p:host}");
  });

  it("rejects a value that is neither a URL nor only property references", () => {
    expect(validateUrlWithProperties({ value: "not a url" })).toBe(false);
    expect(validateUrlWithProperties({ value: "https://${p:host" })).toBe(false);
  });
});
