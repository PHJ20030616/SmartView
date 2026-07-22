import { Navigate, Outlet, useLocation } from "react-router-dom";

import { getSafeRedirectPath } from "./authRedirect";
import { useAuth } from "./AuthContext";

type AuthLocationState = {
  from?: unknown;
};

export function AnonymousOnlyRoute() {
  const { isAuthenticated } = useAuth();
  const location = useLocation();

  if (!isAuthenticated) {
    return <Outlet />;
  }

  const state = location.state as AuthLocationState | null;
  const redirectParam = new URLSearchParams(location.search).get("redirect");
  const redirectPath = getSafeRedirectPath(state?.from ?? redirectParam);
  return <Navigate to={redirectPath} replace />;
}
