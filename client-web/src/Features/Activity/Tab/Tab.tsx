//@ts-nocheck
import React from "react";
import { NavLink } from "react-router-dom";
import styles from "./Tab.module.scss";

type Props = {
  label: string;
  isActive: string;
  rest: any;
};

const Tab = ({ isActive, label, ...rest }: Props) => {
  return (
    <NavLink className={styles.tab} activeClassName={isActive ? styles.activeTab : undefined} {...rest}>
      {label}
    </NavLink>
  );
};

export default Tab;
