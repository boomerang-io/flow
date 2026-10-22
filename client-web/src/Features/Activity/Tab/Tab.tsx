//@ts-nocheck
import React from "react";
import cx from "classnames";
import { NavLink } from "react-router-dom";
import styles from "./Tab.module.scss";

type Props = {
  label: string;
  isActive: string;
  rest: any;
};

const Tab = ({ isActive, label, ...rest }: Props) => {
  return (
    <NavLink className={({ isActive: routeIsActive }) => cx(styles.tab, { [styles.activeTab]: isActive && routeIsActive })} {...rest}>
      {label}
    </NavLink>
  );
};

export default Tab;
