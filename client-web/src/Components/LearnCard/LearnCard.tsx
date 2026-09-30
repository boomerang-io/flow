import React from "react";
import { Tag } from "@carbon/react";
import { Launch } from "@carbon/react/icons";
import styles from "./learnCard.module.scss";

interface CardProps {
  title: string;
  description: string;
  tags: string[];
  link: string;
  icon: React.ReactNode;
}

/**
 * One row in Home's "Learn" list, linking to the Boomerang docs.
 *
 * Uses <a> for absolute urls to avoid basename being prefixed to the link.
 */
function LearnCard({ title, description, tags, link, icon }: CardProps) {
  return (
    <a className={styles.row} href={link} target="_blank" rel="noreferrer">
      <span className={styles.icon} aria-hidden="true">
        {icon}
      </span>
      <span className={styles.body}>
        <span className={styles.title} title={title} data-testid="card-title">
          {title}
          <Launch size={12} className={styles.launch} aria-label="Opens the docs in a new tab" />
        </span>
        <span className={styles.description} title={description}>
          {description}
        </span>
      </span>
      <span className={styles.tags}>
        {tags.map((tag) => (
          <Tag key={tag} type="teal" size="sm">
            {tag}
          </Tag>
        ))}
      </span>
    </a>
  );
}

export default LearnCard;
