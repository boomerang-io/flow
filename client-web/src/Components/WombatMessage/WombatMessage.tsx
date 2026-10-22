import React from "react";
import { Box } from "reflexbox";
import { ErrorPage } from "@boomerang-io/carbon-addons-boomerang-react";
import Wombat from "../WombatSuccessGraphic";

interface WombatSuccessMessageProps {
  className?: string;
  title?: string;
}

const WombatSuccessMessage: React.FC<WombatSuccessMessageProps> = ({ className, ...rest }) => {
  return (
    <Box className={className}>
      <ErrorPage graphic={<Wombat />} {...rest} />
    </Box>
  );
};

export default WombatSuccessMessage;
