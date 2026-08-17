import React from "react";
import { Tag } from "@carbon/react";
import { Launch } from "@carbon/react/icons";
import { Link } from "react-router-dom";
import styles from "./learnCard.module.scss";

interface CardProps {
  title: string;
  description: string;
  tags: string[];
  link: string;
  icon: React.ReactNode;
}

/**
 * The Card component link to the Boomerang Docs
 *
 * Uses <a> for absolute urls to avoid basename being prefixed to the link
 *
 * @param title
 * @param description
 * @param tags
 * @param link
 * @param icon
 *
 * @returns {JSX.Element}
 */
function LearnCard({ title, description, tags, link, icon }: CardProps) {
  return (
    <div className={styles.container}>
      <a href={link} target="_blank">
        <div className={styles.content}>
          <div className={styles.image}>{icon}</div>
          <h1 title={title} className={styles.name} data-testid="card-title">
            {title}
          </h1>
          <p title={description} className={styles.description}>
            {description}
          </p>
        </div>
        <div className={styles.bottom}>
          {tags.map((t) => (
            <Tag className={styles.tag}>{t}</Tag>
          ))}
          {/* Change to external link icon */}
          <Launch size={24} className={styles.ctaIcon} />
        </div>
      </a>
    </div>
  );
}

export default LearnCard;
