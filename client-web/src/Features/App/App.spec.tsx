import { buildFeatureFlags } from "./App";
import { featureFlags as featureFlagsFixture } from "ApiServer/fixtures";

// Regression guard for the failure mode where a backend settings-key rename (e.g. the
// "team.*" -> "workspace.*" feature-flag keys) lands in the /features response but the
// frontend's `feature["..."]` reads are not updated in lock-step: every flag silently
// resolves to `undefined`, with no error anywhere, and features render as disabled for
// every user regardless of configuration.
describe("App --- buildFeatureFlags", () => {
  test("every flag resolves to a boolean for the /features fixture shape", () => {
    const flags = buildFeatureFlags(featureFlagsFixture.features);

    Object.entries(flags).forEach(([flagName, value]) => {
      expect(
        typeof value,
        `${flagName} resolved to ${JSON.stringify(value)} - the key it reads must exist on the /features response`
      ).toBe("boolean");
    });
  });
});
