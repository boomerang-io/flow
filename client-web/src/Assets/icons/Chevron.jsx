import React from "react";
import PropTypes from "prop-types";

const Chevron = ({ className, ...rest }) => {
  return (
    <svg
      className={className}
      width="8px"
      height="12px"
      viewBox="0 0 8 12"
      version="1.1"
      xmlns="http://www.w3.org/2000/svg"
      {...rest}
    >
      <g id="Symbols" stroke="none" strokeWidth="1" fill="none" fillRule="evenodd">
        <g id="launchpad-/-tool-card" transform="translate(-168.000000, -89.000000)">
          <g id="tool-card">
            <g id="Group-7">
              <g id="Group" transform="translate(166.000000, 89.000000)">
                <rect id="Rectangle-22" x="0" y="0" width="12" height="12" />
                <polygon
                  id="Combined-Shape"
                  fill="#40D5BB"
                  transform="translate(6.000000, 6.000000) scale(1, -1) rotate(-360.000000) translate(-6.000000, -6.000000) "
                  points="2.57142857 9.45441559 2.57142857 12 9.42857143 6 2.57142857 2.30026081e-13 2.57142857 2.54558441 6.5193321 6"
                />
              </g>
            </g>
          </g>
        </g>
      </g>
    </svg>
  );
};

Chevron.propTypes = {
  className: PropTypes.string,
};

export default Chevron;
