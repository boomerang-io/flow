import { Error404 } from "@boomerang-io/carbon-addons-boomerang-react";
import {
  StructuredListCell,
  StructuredListBody,
  StructuredListHead,
  StructuredListRow,
  StructuredListWrapper,
} from "@carbon/react";
import React from "react";
import styles from "./resultsTable.module.scss";

type Props = {
  data: Array<{
    key: string;
    value: string;
  }>;
  hasJsonValues?: boolean;
};

// A value that is a JSON object or array reads better indented; anything else shows as it is.
function prettyValue(value: unknown) {
  if (typeof value !== "string") {
    return typeof value === "object" && value !== null ? JSON.stringify(value, null, 2) : String(value);
  }
  try {
    const parsed = JSON.parse(value);
    return typeof parsed === "object" && parsed !== null ? JSON.stringify(parsed, null, 2) : value;
  } catch {
    return value;
  }
}

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
        <StructuredListWrapper className={styles.table}>
          <StructuredListHead>
            <StructuredListRow head>
              <StructuredListCell head>Name</StructuredListCell>
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
                    <pre className={styles.code}>{prettyValue(formatPropertyValue(property.value))}</pre>
                  </StructuredListCell>
                </StructuredListRow>
              ))}
          </StructuredListBody>
        </StructuredListWrapper>
      ) : (
        <p>No results to display</p>
      )}
    </div>
  );
}

export default PropertiesTable;
