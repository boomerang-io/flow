import React from "react";
import { Breadcrumb, BreadcrumbItem } from "@carbon/react";
import { Link } from "react-router-dom";
import { appLink } from "Config/appConfig";

// The breadcrumb every Administer page carries, so admin headers have the same rows as the rest.
export default function AdminBreadcrumb() {
  return (
    <Breadcrumb noTrailingSlash>
      <BreadcrumbItem>
        <Link to={appLink.home()}>Home</Link>
      </BreadcrumbItem>
      <BreadcrumbItem isCurrentPage>
        <p>Administer</p>
      </BreadcrumbItem>
    </Breadcrumb>
  );
}
