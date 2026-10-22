import React from "react";

const FailureIcon = (props: React.SVGProps<SVGSVGElement>) => (
  <svg width="26px" height="26px" viewBox="0 0 26 26" {...props}>
    <title>{"logic-failure"}</title>
    <g id="30-editor" stroke="none" strokeWidth={1} fill="none" fillRule="evenodd">
      <g id="logic-failure" transform="translate(1.000000, 1.000000)">
        <circle id="Oval" stroke="#0072C3" fill="#FFFFFF" cx={12} cy={12} r={12} />
        <g id="Organization-/-Status-/-misuse--outline-/-16" transform="translate(4.000000, 4.000000)" fill="#A2191F">
          <path
            d="M8,1 C4.1,1 1,4.1 1,8 C1,11.9 4.1,15 8,15 C11.9,15 15,11.9 15,8 C15,4.1 11.9,1 8,1 Z M8,14 C4.7,14 2,11.3 2,8 C2,4.7 4.7,2 8,2 C11.3,2 14,4.7 14,8 C14,11.3 11.3,14 8,14 Z M10.7,11.5 L8,8.8 L5.3,11.5 L4.5,10.7 L7.2,8 L4.5,5.3 L5.3,4.5 L8,7.2 L10.7,4.5 L11.5,5.3 L8.8,8 L11.5,10.7 L10.7,11.5 Z"
            id="Fill"
          />
        </g>
      </g>
    </g>
  </svg>
);

export default FailureIcon;
