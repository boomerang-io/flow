import { Navigate } from "react-router-dom";

export default function RootRedirectRoute() {
  return <Navigate to="/home" replace />;
}
