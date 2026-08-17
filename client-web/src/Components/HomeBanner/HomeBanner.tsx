//@ts-nocheck
import styles from "./homeBanner.module.scss";

interface HomeBannerProps {
  name: string;
}

export default function HomeBanner({ name }: HomeBannerProps) {
  return (
    <div className={styles.container}>
      <div className={styles.content}>
        <h1 className={styles.title}>{name}</h1>
        <p className={styles.subtitle}>Turn ideation into automation.</p>
      </div>
    </div>
  );
}
