import React from "react";
import { Tag } from "@carbon/react";
import styles from "./ModalContent.module.scss";

export type IntegrationField = {
  name: string;
  label?: string;
  description?: string;
  type?: string;
  required?: boolean;
};

interface IntegrationParametersProps {
  title: string;
  note: string;
  fields: Array<IntegrationField>;
}

/**
 * The workspace parameters an integration manages, each by the reference a task reads it with, so
 * a workflow author sees before connecting what connecting adds. A password field is marked secret.
 */
export default function IntegrationParameters({ title, note, fields }: IntegrationParametersProps) {
  if (fields.length === 0) {
    return null;
  }
  return (
    <section className={styles.parameters} aria-label={title} data-testid="integration-parameters">
      <h3>{title}</h3>
      <p className={styles.note}>{note}</p>
      <dl className={styles.parameterList}>
        {fields.map((field) => (
          <div key={field.name}>
            <dt>
              <code>{`$(workspace.params.${field.name})`}</code>
              {field.type === "password" && (
                <Tag size="sm" type="gray">
                  secret
                </Tag>
              )}
            </dt>
            <dd>
              <span>{field.label ?? field.name}</span>
              {field.description && <span className={styles.parameterDescription}>{field.description}</span>}
            </dd>
          </div>
        ))}
      </dl>
    </section>
  );
}
