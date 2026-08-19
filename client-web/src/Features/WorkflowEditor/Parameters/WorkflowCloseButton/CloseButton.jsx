import React from "react";

const CloseButton = (props) => (
  <svg width="26px" height="26px" viewBox="0 0 26 26" {...props}>
    <title>{"close-button"}</title>
    <g id="30-editor" stroke="none" strokeWidth={1} fill="none" fillRule="evenodd">
      <g id="close-button" transform="translate(1.000000, 1.000000)">
        <circle id="Oval" stroke="#0072C3" fill="#FFFFFF" cx={12} cy={12} r={12} />
        <g id="Actions-/-Navigation-/-close-/-24" transform="translate(4.000000, 4.000000)" fill="#000000">
          <polygon id="Fill" points="12 4.7 11.3 4 8 7.3 4.7 4 4 4.7 7.3 8 4 11.3 4.7 12 8 8.7 11.3 12 12 11.3 8.7 8" />
        </g>
      </g>
    </g>
  </svg>
);

export default CloseButton;
