//@ts-nocheck
//https://developers.google.com/web/updates/2017/03/performant-expand-and-collapse

export function createExpandKeyframeAnimation(collapsedNode, expandedNode) {
  // Figure out the size of the element when collapsed.
  let { x, y } = calculateCollapsedScale();
  let animation = "";
  let inverseAnimation = "";

  for (let step = 0; step <= 100; step++) {
    // Remap the step value to an eased one.
    let easedStep = ease(step / 100);

    // Calculate the scale of the element.
    const xScale = x + (1 - x) * easedStep;
    const yScale = y + (1 - y) * easedStep;

    animation += `${step}% {
      transform: scale(${xScale}, ${yScale});
    }`;

    // And now the inverse for the contents.
    const invXScale = 1 / xScale;
    const invYScale = 1 / yScale;
    inverseAnimation += `${step}% {
      transform: scale(${invXScale}, ${invYScale});
    }`;
  }

  return `
  @keyframes menuAnimation {
    ${animation}
  }

  @keyframes menuContentsAnimation {
    ${inverseAnimation}
  }`;
}

export function createCollapseKeyframeAnimation(collapsedNode, expandedNode) {
  // Figure out the size of the element when collapsed.
  let { x, y } = calculateCollapsedScale();
  let animation = "";
  let inverseAnimation = "";

  for (let step = 0; step <= 100; step++) {
    // Remap the step value to an eased one.
    let easedStep = ease(step / 100);

    // Calculate the scale of the element.
    const xScale = 1 + (x - 1) * easedStep;
    const yScale = 1 + (y - 1) * easedStep;

    animation += `${step}% {
      transform: scale(${xScale}, ${yScale});
    }`;

    // And now the inverse for the contents.
    const invXScale = 1 / xScale;
    const invYScale = 1 / yScale;
    inverseAnimation += `${step}% {
      transform: scale(${invXScale}, ${invYScale});
    }`;
  }

  return `
  @keyframes menuAnimation {
    ${animation}
  }

  @keyframes menuContentsAnimation {
    ${inverseAnimation}
  }`;
}

function calculateCollapsedScale(collapsedNode, expandedNode) {
  // The menu title can act as the marker for the collapsed state.
  const collapsed = collapsedNode.getBoundingClientRect();

  // Whereas the menu as a whole (title plus items) can act as
  // a proxy for the expanded state.
  const expanded = expandedNode.getBoundingClientRect();
  return {
    x: collapsed.width / expanded.width,
    y: collapsed.height / expanded.height
  };
}

function ease(v, pow = 4) {
  return 1 - Math.pow(1 - v, pow);
}
