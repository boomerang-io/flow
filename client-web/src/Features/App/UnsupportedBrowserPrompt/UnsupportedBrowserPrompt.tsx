import React from "react";
import { Button } from "@carbon/react";
import { Error403 } from "@boomerang-io/carbon-addons-boomerang-react";
import { ArrowRight } from "@carbon/react/icons";

const UnsupportedBrowserPrompt = ({ onDismissWarning }: { onDismissWarning: () => void }) => {
  return (
    <div style={{ textAlign: "center", paddingTop: "3rem" }}>
      <Error403
        header={null}
        message={
          <>
            <p style={{ marginBottom: "2rem" }}>
              Your experience may be degraded if you aren't using a recent version of Chrome, Firefox, Safari or Edge.
            </p>
            <Button iconDescription="Continue" onClick={onDismissWarning} renderIcon={ArrowRight}>
              Continue, anyway
            </Button>
          </>
        }
        theme="boomerang"
      />
    </div>
  );
};

export default UnsupportedBrowserPrompt;
