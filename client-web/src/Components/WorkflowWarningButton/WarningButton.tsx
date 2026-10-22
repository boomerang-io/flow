import * as React from "react";

function SvgComponent(props: any) {
  return (
    <svg width={26} height={26} viewBox="0 0 26 26" {...props}>
      <title>{"x"}</title>
      <g transform="translate(1 1)" fill="none" fillRule="evenodd">
        <circle stroke="#0072C3" fill="#F9C100" cx={12} cy={12} r={12} />
        <path
          d="M18.5 18h-13c-.2 0-.3-.1-.4-.2-.1-.2-.1-.3 0-.5l6.5-12c.1-.3.4-.4.6-.2.1 0 .2.1.2.2l6.5 12c.1.2.1.3 0 .5 0 .1-.2.2-.4.2zM6.3 17h11.3L12 6.5 6.3 17zm5.2-7h1v3.5h-1V10zm.5 4.8c-.4 0-.8.3-.8.8s.3.8.8.8c.4 0 .8-.3.8-.8s-.4-.8-.8-.8z"
          fill="#000"
        />
      </g>
    </svg>
  );
}

export default SvgComponent;
