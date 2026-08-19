import { Stack, SkeletonPlaceholder, SkeletonText } from "@carbon/react";
import styles from "./workflowCard.module.scss";

const WorkflowCardSkeleton = () => {
  return (
    <Stack gap={4}>
      <SkeletonText className={styles.skeletonText} />
      <div className={styles.skeletonCardContainer}>
        <SkeletonPlaceholder className={styles.skeletonCard} />
        <SkeletonPlaceholder className={styles.skeletonCard} />
        <SkeletonPlaceholder className={styles.skeletonCard} />
      </div>
    </Stack>
  );
};

export default WorkflowCardSkeleton;
