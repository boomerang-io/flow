import {
  StructuredListCell,
  StructuredListBody,
  StructuredListHead,
  StructuredListRow,
  StructuredListWrapper,
} from "@carbon/react";
import React from "react";
import styles from "./propertiesTable.module.scss";

type Props = {
  data:
    | {
        key: string;
        value: string;
      }[]
    | {
        [key: string]: string;
      };
  hasJsonValues: boolean;
};

function PropertiesTable({ data: properties, hasJsonValues = false }: Props) {
  const formatPropertyValue = (value: string) => {
    if (hasJsonValues) {
      if (value && value !== '""')
        try {
          return JSON.parse(value);
        } catch {
          return "---";
        }
      return "---";
    } else {
      return value ?? "---";
    }
  };
  const hasProperties = Array.isArray(properties) ? properties.length > 0 : Boolean(properties) && Object.keys(properties).length > 0;

  return (
    <div className={styles.tableContainer}>
      {hasProperties ? (
        <StructuredListWrapper selection>
          <StructuredListHead>
            <StructuredListRow head>
              <StructuredListCell head>Name</StructuredListCell>
              <StructuredListCell head>Value</StructuredListCell>
            </StructuredListRow>
          </StructuredListHead>
          <StructuredListBody>
            {Array.isArray(properties) &&
              properties.map((property: { key: string; value: string; description?: string }, i: number) => (
                <StructuredListRow key={`row-${i}`}>
                  <StructuredListCell>{property.key}</StructuredListCell>
                  <StructuredListCell>
                    {<code className={styles.code}>{formatPropertyValue(property.value)}</code>}
                  </StructuredListCell>
                </StructuredListRow>
              ))}
          </StructuredListBody>
        </StructuredListWrapper>
      ) : (
        <p>No params to display</p>
      )}
    </div>
  );
}

export default PropertiesTable;
