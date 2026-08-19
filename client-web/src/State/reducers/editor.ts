//@ts-nocheck
import { useEffect, useState } from "react";
import { createContainer } from "react-tracked";

// Reference: https://react-tracked.js.org/docs/recipes#usestate-with-propstate
function useValue({ propState }) {
  const [state, setState] = useState(propState);
  useEffect(() => {
    // or useLayoutEffect
    setState(propState);
  }, [propState]);
  return [state, setState];
}

export const { Provider, useTrackedState } = createContainer(useValue);
