//@ts-nocheck
import React, { Component } from "react";
import PropTypes from "prop-types";
import Transition from "react-transition-group/Transition";

// Declared so consumers typecheck against the real prop surface (the file itself stays
// unchecked: the class body predates TypeScript here and leans on defaultProps).
type FadeAnimationProps = {
  animationDuration?: number;
  animationFunction?: string;
  timeout?: number;
  className?: string;
  renderDelay?: number;
  transitionStyles?: Record<string, React.CSSProperties>;
  children: React.ReactNode;
};

class FadeAnimation extends Component<FadeAnimationProps, { in: boolean }> {
  constructor(props: FadeAnimationProps) {
    super(props);
    this.state = {
      in: false
    };
  }

  componentDidMount() {
    this.timer = setTimeout(() => {
      this.setState({
        in: true
      });
    }, this.props.renderDelay);
  }

  render() {
    const defaultStyle = {
      transition: `opacity ${this.props.animationDuration}ms ${this.props.animationFunction}`,
      opacity: 0
    };
    return (
      <Transition in={this.state.in} timeout={this.props.timeout} unmountOnExit={true}>
        {state => (
          <div
            className={this.props.className}
            style={{
              ...defaultStyle,
              ...this.props.transitionStyles[state]
            }}
          >
            {this.props.children}
          </div>
        )}
      </Transition>
    );
  }
}

FadeAnimation.propTypes = {
  animationDuration: PropTypes.number,
  animationFunction: PropTypes.string,
  timeout: PropTypes.number,
  className: PropTypes.string,
  renderDelay: PropTypes.number,
  transitionStyles: PropTypes.object,
  children: PropTypes.any.isRequired
};

FadeAnimation.defaultProps = {
  animationDuration: 500,
  animationFunction: "ease-in-out",
  renderDelay: 100,
  timeout: 500,
  className: "",
  transitionStyles: {
    appearing: { opacity: 1 },
    appeared: { opacity: 1 },
    entering: { opacity: 1 },
    entered: { opacity: 1 }
  }
};

export default FadeAnimation;
