import { retryInitialValues, splitRetryValues } from "./retry";

describe("retry form values", () => {
  it("starts blank, exponential, for a task with no retry", () => {
    expect(retryInitialValues(undefined)).toEqual({
      retryMax: "",
      retryBackoffType: "exponential",
      retryBackoffInitial: "",
      retryBackoffMax: "",
    });
  });

  it("reads a stored retry into the form", () => {
    expect(retryInitialValues({ max: 3, backoff: { type: "fixed", initial: 5, max: 60 } })).toEqual({
      retryMax: "3",
      retryBackoffType: "fixed",
      retryBackoffInitial: "5",
      retryBackoffMax: "60",
    });
  });

  it("saves blank and zero retries as no retry, and keeps the params", () => {
    const blank = splitRetryValues({ greeting: "hi", ...retryInitialValues(undefined) });
    expect(blank.retry).toBeUndefined();
    expect(blank.rest).toEqual({ greeting: "hi" });

    expect(splitRetryValues({ ...retryInitialValues(undefined), retryMax: "0" }).retry).toBeUndefined();
  });

  it("saves only the fields that were set", () => {
    expect(splitRetryValues({ ...retryInitialValues(undefined), retryMax: "2" }).retry).toEqual({ max: 2 });
    expect(
      splitRetryValues({ ...retryInitialValues(undefined), retryMax: "2", retryBackoffInitial: "20", retryBackoffMax: "120" })
        .retry,
    ).toEqual({ max: 2, backoff: { initial: 20, max: 120 } });
  });

  it("drops the maximum wait for a fixed backoff", () => {
    expect(
      splitRetryValues({
        retryMax: "4",
        retryBackoffType: "fixed",
        retryBackoffInitial: "15",
        retryBackoffMax: "120",
      }).retry,
    ).toEqual({ max: 4, backoff: { type: "fixed", initial: 15 } });
  });
});
