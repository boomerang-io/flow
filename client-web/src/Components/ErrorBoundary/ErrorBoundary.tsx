import React from "react";
import { Flex } from "reflexbox";
import { ErrorBoundary as ReactErrorBoundary, FallbackProps } from "react-error-boundary";
import { ErrorFullPage } from "@boomerang-io/carbon-addons-boomerang-react";
import { CORE_ENV_URL } from "Config/appConfig";

const ErrorMessage: React.FC<FallbackProps> = (props) => {
  return (
    <Flex alignItems="center" flexDirection="column" justifyContent="center">
      <ErrorFullPage statusUrl={`${CORE_ENV_URL}/status`} />
    </Flex>
  );
};

interface ErrorBoundaryProps {
  FallbackComponent?: React.FunctionComponent<FallbackProps>;
}

const ErrorBoundary: React.FC<ErrorBoundaryProps> = ({ children, FallbackComponent = ErrorMessage, ...rest }) => {
  return (
    <ReactErrorBoundary FallbackComponent={ErrorMessage} {...rest}>
      {children}
    </ReactErrorBoundary>
  );
};

export default ErrorBoundary;
