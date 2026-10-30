import AlwaysIcon from "Assets/workflowLinkIcons/AlwaysIcon";
import FailureIcon from "Assets/workflowLinkIcons/FailureIcon";
import FinallyIcon from "Assets/workflowLinkIcons/FinallyIcon";
import SuccessIcon from "Assets/workflowLinkIcons/SuccessIcon";
import { EdgeExecutionCondition } from "Constants";
import { EdgeExecutionConditionType } from "Types";
//import { ArrowRight, Checkmark, Close } from "@carbon/react/icons";


export const EXECUTION_CONDITIONS: Array<{name: EdgeExecutionConditionType, Icon: (props:React.SVGProps<SVGSVGElement>) =>JSX.Element}> = [
  {
    Icon: SuccessIcon,
    name: EdgeExecutionCondition.Success,
  },
  {
    Icon: FailureIcon,
    name: EdgeExecutionCondition.Failure,
  },
  {
    Icon: AlwaysIcon,
    name: EdgeExecutionCondition.Always,
  },
];

// Not part of EXECUTION_CONDITIONS: that array drives the three-way switcher on ordinary links,
// and a finally link (from End) never cycles.
export const FINALLY_CONDITION: { name: EdgeExecutionConditionType; Icon: (props: React.SVGProps<SVGSVGElement>) => JSX.Element } = {
  Icon: FinallyIcon,
  name: EdgeExecutionCondition.Finally,
};
