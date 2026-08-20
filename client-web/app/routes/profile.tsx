import UserProfile, { action } from "Features/UserProfile/UserProfile";

// See app/routes/globalParameters.tsx - ssr:true means this runs server-side now. There is no
// loader: the profile record comes from the root bootstrap loader (Features/App/App.tsx) via
// useAppContext(); only this page's writes are route-owned.
export { action };

export default function ProfileRoute() {
  return <UserProfile />;
}
