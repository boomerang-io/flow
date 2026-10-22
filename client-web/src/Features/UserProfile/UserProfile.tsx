import React from "react";
import { useFeature } from "flagged";
import { formatErrorMessage } from "@boomerang-io/utils";
import { useAppContext } from "Hooks";
import Header from "./Header";
import Settings from "./Settings";
import { FeatureFlag } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import { serverFetch } from "Config/serverFetch";
import { HttpMethod } from "Constants";
import { actionError, type ActionError } from "Utils/actionResult";
import styles from "./UserProfile.module.scss";

// Route module for app/routes/profile.tsx. There is no `loader` here: the profile record this
// page renders is already fetched by the root bootstrap loader (Features/App/App.tsx) and read
// off useAppContext(), so only the writes move onto the route - the same read-stays/writes-move
// split as Features/Parameters/WorkspaceParameters/WorkspaceParameters.tsx.
//
// One action serves both write sites on this page (UpdateBasicDetails and Settings' close-account
// confirm), keyed by an `intent` form field; each calls it through a bare useFetcher(), which
// resolves to the nearest matched route's action - this one.

type ActionResult =
  | { intent: "updateProfile" | "deleteAccount" }
  | ({ intent: "updateProfile" | "deleteAccount" } & ActionError)
  // Consumers narrow on `intent`, so an "unknown" result is inert for them - the same shape
  // tokenAction and editorAction return for an intent they do not own.
  | ({ intent: "unknown" } & ActionError);

const PROFILE_INTENTS = ["updateProfile", "deleteAccount"] as const;

/*
 * SECURITY - the identity these writes act on is never taken from the browser.
 *
 * `serverFetch(request)` forwards the inbound request's `Cookie` header onto the outbound API
 * call (see Config/serverFetch.ts); that cookie is the caller's session, so the API resolves
 * WHICH user this is server-side. Two consequences that must not be regressed:
 *
 *  1. Never call the API from a loader/action with a bare browser `axios` instance (the old
 *     `resolver` object, deleted with the BFF teardown, relied on a global
 *     `axios.defaults.withCredentials`, which only does anything in a browser with a cookie
 *     jar) - in Node there is none, so the request goes out with no credentials at all and the
 *     API sees an anonymous caller.
 *  2. The basic-details update targets `PATCH /profile`, NOT `PATCH /user/{userId}`. The
 *     profile route derives the subject from the session
 *     (ProfileControllerV2.updateProfile -> UserService.updateCurrentProfile), so there is no
 *     user id on the wire for a caller to tamper with and no way to update someone else's
 *     profile through it. The close-account path has no self-scoped route available (only
 *     `DELETE /user/{userId}`), so the id is resolved here from `GET /profile` - i.e. from the
 *     session - rather than trusted from the submitted form.
 */
export async function action({ request }: { request: Request }) {
  const api = serverFetch(request);
  const formData = await request.formData();
  const intent = String(formData.get("intent"));

  /*
   * Rejecting anything unrecognised is load-bearing, not defensive tidiness - the same trap
   * tokenAction and editorAction document. app/routes/profile.tsx dispatches EVERY non-token
   * intent to this action, and the profile PATCH used to be the fall-through branch: a stray or
   * malformed submission sent `displayName: ""` and blanked the user's display name.
   */
  if (!(PROFILE_INTENTS as readonly string[]).includes(intent)) {
    return actionError({
      intent: "unknown" as const,
      error: {
        title: "Unsupported Profile Action",
        message: `The profile action does not handle the "${intent}" intent.`,
      },
    });
  }

  if (intent === "deleteAccount") {
    try {
      const profile = await api.get(serviceUrl.getUserProfile());
      await api.delete(serviceUrl.deleteUser({ userId: profile.data.id }));
      return { intent: "deleteAccount" as const };
    } catch (error) {
      return actionError({
        intent: "deleteAccount" as const,
        error: formatErrorMessage({ error, defaultMessage: "Unable to close the account." }),
      });
    }
  }

  // Reachable only for intent === "updateProfile" now.
  const displayName = String(formData.get("displayName") ?? "");
  try {
    await api({ url: serviceUrl.getUserProfile(), data: { displayName }, method: HttpMethod.Patch });
    return { intent: "updateProfile" as const };
  } catch (error) {
    return actionError({
      intent: "updateProfile" as const,
      error: formatErrorMessage({ error, defaultMessage: "Failed to update profile" }),
    });
  }
}

export type { ActionResult as UserProfileActionResult };

function UserProfile() {
  const userManagementEnabled = useFeature(FeatureFlag.UserManagementEnabled);
  const { user } = useAppContext();

  return (
    <div className={styles.container}>
      <Header user={user} userManagementEnabled={userManagementEnabled} />
      <Settings user={user} userManagementEnabled={userManagementEnabled} />
    </div>
  );
}

export default UserProfile;
