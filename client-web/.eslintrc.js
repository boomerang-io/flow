module.exports = {
  extends: ["react-app", "plugin:jsx-a11y/recommended", "plugin:testing-library/react"],
  plugins: ["jsx-a11y", "react-hooks", "testing-library"],
  globals: {
    shallow: true,
    render: true,
    mount: true,
    renderer: true,
    rtlRender: true,
    rtlRouterRender: true,
    rtlContextRouterRender: true,
  },
  ignorePatterns: ["public/*"],
};
