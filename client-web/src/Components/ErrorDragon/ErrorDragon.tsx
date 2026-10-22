import React from "react";
import { ErrorFullPage } from "@boomerang-io/carbon-addons-boomerang-react";
import { CORE_ENV_URL } from "Config/appConfig";

const ErrorDragon: React.FC<any> = (props) => {
  return <ErrorFullPage statusUrl={`${CORE_ENV_URL}/support`} {...props} />;
};

export default ErrorDragon;
