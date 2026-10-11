import React from "react";
import styles from "./homeBanner.module.scss";

interface HomeBannerProps {
  /** Small uppercase line above the title: the date and the product's configured name. */
  eyebrow: string;
  title: string;
  message: string;
  /** Primary calls to action, rendered on the band's right edge. */
  actions?: React.ReactNode;
}

/**
 * The hero band at the top of Home: the one place the page speaks to the person by name and says
 * what happened today. The product name is never hard-coded here - Home passes the platform's
 * configured name (useAppContext().name) in the eyebrow.
 */
export default function HomeBanner({ eyebrow, title, message, actions }: HomeBannerProps) {
  return (
    <section className={styles.container} aria-label="Welcome">
      <div className={styles.content}>
        <p className={styles.eyebrow}>{eyebrow}</p>
        <h1 className={styles.title}>{title}</h1>
        <p className={styles.message}>{message}</p>
      </div>
      {actions ? <div className={styles.actions}>{actions}</div> : null}
    </section>
  );
}
