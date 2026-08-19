import { Stack, SkeletonPlaceholder } from "@carbon/react";

import styles from "./integrationCard.module.scss";

const IntegrationCardSkeleton = () => {
  return (
    <Stack gap={4}>
      <div className={styles.skeletonCardContainer}>
        <SkeletonPlaceholder className={styles.skeletonCard} />
        <SkeletonPlaceholder className={styles.skeletonCard} />
        <SkeletonPlaceholder className={styles.skeletonCard} />
      </div>
    </Stack>
  );
};

export default IntegrationCardSkeleton;
