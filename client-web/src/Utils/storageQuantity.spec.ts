import { describe, expect, it } from "vitest";
import { gbToQuantity, quantityToGb } from "./storageQuantity";

describe("storageQuantity", () => {
  it("reads Kubernetes quantities as whole gigabytes", () => {
    expect(quantityToGb("2Gi")).toBe(2);
    expect(quantityToGb("512Mi")).toBe(1);
    expect(quantityToGb("1Ti")).toBe(1024);
    expect(quantityToGb("4")).toBe(4);
    expect(quantityToGb(3)).toBe(3);
  });

  it("falls back when the size is absent or unreadable", () => {
    expect(quantityToGb(undefined)).toBe(1);
    expect(quantityToGb("")).toBe(1);
    expect(quantityToGb("lots", 2)).toBe(2);
  });

  it("writes gigabytes as a Gi quantity", () => {
    expect(gbToQuantity(2)).toBe("2Gi");
  });
});
