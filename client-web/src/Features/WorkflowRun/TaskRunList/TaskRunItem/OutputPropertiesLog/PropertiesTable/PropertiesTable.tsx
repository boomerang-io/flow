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
  return (
    <div className={styles.tableContainer}>
      {properties && properties.length > 0 ? (
        <StructuredListWrapper selection>
          <StructuredListHead>
            <StructuredListRow head>
              <StructuredListCell head>Parameter</StructuredListCell>
              {!hasJsonValues && <StructuredListCell head>Description</StructuredListCell>}
              <StructuredListCell head>Value</StructuredListCell>
            </StructuredListRow>
          </StructuredListHead>
          <StructuredListBody>
            {Array.isArray(properties) &&
              properties.map((property: { key: string; value: string; description?: string }, i: number) => (
                <StructuredListRow key={`row-${i}`}>
                  <StructuredListCell>{property.key}</StructuredListCell>
                  {!hasJsonValues && <StructuredListCell>{property?.description ?? "---"}</StructuredListCell>}
                  <StructuredListCell>
                    {<code className={styles.code}>{formatPropertyValue(property.value)}</code>}
                  </StructuredListCell>
                </StructuredListRow>
              ))}
          </StructuredListBody>
        </StructuredListWrapper>
      ) : (
        <p>No parameters to display</p>
      )}
    </div>
  );
}

export default PropertiesTable;
