import React, { useState } from "react";
import {
  Button,
  DataTable,
  DataTableSkeleton,
  OverflowMenu,
  OverflowMenuItem,
  Pagination,
  Tag,
  TableToolbar,
  TableToolbarContent,
  TableToolbarSearch,
} from "@carbon/react";
import { Add } from "@carbon/react/icons";
import { ConfirmModal, Error, ToastNotification, notify } from "@boomerang-io/carbon-addons-boomerang-react";
import { matchSorter } from "match-sorter";
import EmptyState from "Components/EmptyState";
import { InputType, InputTypeCopy, PASSWORD_CONSTANT } from "Constants";
import { DataDrivenInput } from "Types";
import styles from "./parametersTable.module.scss";

export type ParameterScope = "workflow" | "workspace" | "global";

const DEFAULT_PAGE_SIZE = 10;
const PAGE_SIZES = [DEFAULT_PAGE_SIZE, 25, 50];

/** How a task or workflow reads a parameter of this scope. */
export function parameterReference(scope: ParameterScope, name: string): string {
  const prefix = scope === "workflow" ? "params" : scope === "workspace" ? "workspace.params" : "global.params";
  return `$(${prefix}.${name})`;
}

const valueHeaders: Record<ParameterScope, Array<{ header: string; key: string; sortable?: boolean }>> = {
  workflow: [
    { header: "Type", key: "type", sortable: true },
    { header: "Default value", key: "default" },
  ],
  workspace: [{ header: "Value", key: "value" }],
  global: [{ header: "Value", key: "value" }],
};

function displayValue(parameter: DataDrivenInput, value: unknown): string {
  if (value === undefined || value === null || value === "") {
    return "—";
  }
  if (parameter.type === InputType.Password) {
    return PASSWORD_CONSTANT;
  }
  if (typeof value === "string") {
    return value;
  }
  if (Array.isArray(value)) {
    return value.map((item) => (typeof item === "string" ? item : `${item.key}=${item.value}`)).join(", ");
  }
  return JSON.stringify(value);
}

interface ParametersTableProps {
  scope: ParameterScope;
  parameters: Array<DataDrivenInput>;
  isLoading?: boolean;
  errorLoading?: boolean;
  onAdd: () => void;
  onEdit: (parameter: DataDrivenInput) => void;
  onDelete: (parameter: DataDrivenInput) => void;
}

/**
 * One table for workflow, workspace and global parameters: the name as tasks type it, its label and value,
 * a Required, Read-only or Secured tag, and a menu to edit, copy the reference, or delete.
 */
const ParametersTable: React.FC<ParametersTableProps> = ({
  scope,
  parameters,
  isLoading,
  errorLoading,
  onAdd,
  onEdit,
  onDelete,
}) => {
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [query, setQuery] = useState("");
  const [toDelete, setToDelete] = useState<DataDrivenInput | undefined>();

  const headers = [
    { header: "Name", key: "name", sortable: true },
    { header: "Label", key: "label", sortable: true },
    ...valueHeaders[scope],
    { header: "", key: "tag" },
    { header: "Description", key: "description" },
    { header: "", key: "actions" },
  ];

  const filtered = query
    ? matchSorter(parameters, query, { keys: ["name", "label", "description"], threshold: matchSorter.rankings.CONTAINS })
    : parameters;
  const byId = new Map(filtered.map((parameter, index) => [String(index), parameter]));
  const tableData = filtered.map((parameter, index) => ({
    id: String(index),
    name: parameter.name,
    label: parameter.label,
    type: parameter.type,
    default: parameter.default,
    value: parameter.value,
    tag: "",
    description: parameter.description,
    actions: "",
  }));

  const copyReference = async (parameter: DataDrivenInput) => {
    const reference = parameterReference(scope, parameter.name ?? "");
    try {
      await navigator.clipboard.writeText(reference);
      notify(<ToastNotification kind="success" title="Reference copied" subtitle={reference} />);
    } catch (e) {
      notify(<ToastNotification kind="error" title="Couldn't copy" subtitle={reference} />);
    }
  };

  const renderCell = (parameter: DataDrivenInput, key: string, value: any) => {
    switch (key) {
      case "name":
        return <span className={styles.name}>{value}</span>;
      case "type":
        return InputTypeCopy[value as keyof typeof InputTypeCopy] ?? value ?? "—";
      case "default":
      case "value":
        return <span className={styles.value}>{displayValue(parameter, value)}</span>;
      case "tag":
        if (parameter.readOnly) {
          return <Tag size="sm" type="gray">Read-only</Tag>;
        }
        if (scope === "workflow") {
          return parameter.required ? <Tag size="sm" type="blue">Required</Tag> : null;
        }
        return parameter.type === InputType.Password ? <Tag size="sm" type="purple">Secured</Tag> : null;
      case "actions":
        return parameter.readOnly ? null : (
          <OverflowMenu
            align="left"
            aria-label={`Actions for ${parameter.name}`}
            data-testid="parameter-menu-button"
            flipped
            iconDescription="Parameter actions"
          >
            <OverflowMenuItem itemText="Edit" onClick={() => onEdit(parameter)} />
            <OverflowMenuItem itemText="Copy reference" onClick={() => copyReference(parameter)} />
            <OverflowMenuItem hasDivider isDelete itemText="Delete" onClick={() => setToDelete(parameter)} />
          </OverflowMenu>
        );
      default:
        return value ? <span className={styles.text}>{value}</span> : <span className={styles.none}>—</span>;
    }
  };

  const { TableContainer, Table, TableHead, TableRow, TableBody, TableCell, TableHeader } = DataTable;

  return (
    <div className={styles.tableContainer}>
      {isLoading ? (
        <DataTableSkeleton />
      ) : errorLoading ? (
        <Error />
      ) : (
        <>
          <DataTable
            rows={tableData}
            headers={headers}
            render={({ rows, headers, getHeaderProps }: { rows: any; headers: any; getHeaderProps: any }) => (
              <TableContainer>
                <TableToolbar aria-label="Parameters toolbar">
                  <TableToolbarContent>
                    <TableToolbarSearch
                      persistent
                      placeholder="Search parameters"
                      onChange={(event: any) => {
                        setQuery(event?.target?.value ?? "");
                        setPage(1);
                      }}
                    />
                    <Button data-testid="create-parameter-button" renderIcon={Add} onClick={onAdd}>
                      Add parameter
                    </Button>
                  </TableToolbarContent>
                </TableToolbar>
                {filtered.length > 0 ? (
                  <Table isSortable className={styles.table}>
                    <TableHead>
                      <TableRow>
                        {headers.map((header: any) => (
                          <TableHeader
                            key={header.key}
                            {...getHeaderProps({ header, isSortable: header.sortable })}
                            className={styles[header.key]}
                          >
                            {header.header}
                          </TableHeader>
                        ))}
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {/* Sorted by DataTable across every row, then paged. */}
                      {rows.slice((page - 1) * pageSize, page * pageSize).map((row: any) => {
                        const parameter = byId.get(row.id)!;
                        return (
                          <TableRow key={row.id}>
                            {row.cells.map((cell: any) => (
                              <TableCell key={cell.id} className={styles[cell.info.header]}>
                                {renderCell(parameter, cell.info.header, cell.value)}
                              </TableCell>
                            ))}
                          </TableRow>
                        );
                      })}
                    </TableBody>
                  </Table>
                ) : (
                  <EmptyState
                    title={parameters.length ? "No matching parameters" : "No parameters yet"}
                    message={parameters.length ? null : "Add one to pass a value into every run."}
                  />
                )}
              </TableContainer>
            )}
          />
          {filtered.length > DEFAULT_PAGE_SIZE && (
            <Pagination
              onChange={({ page, pageSize }: { page: number; pageSize: number }) => {
                setPage(page);
                setPageSize(pageSize);
              }}
              page={page}
              pageSize={pageSize}
              pageSizes={PAGE_SIZES}
              totalItems={filtered.length}
            />
          )}
        </>
      )}
      <ConfirmModal
        isOpen={Boolean(toDelete)}
        affirmativeAction={() => {
          if (toDelete) onDelete(toDelete);
          setToDelete(undefined);
        }}
        affirmativeButtonProps={{ kind: "danger" }}
        affirmativeText="Delete"
        negativeText="Cancel"
        onCloseModal={() => setToDelete(undefined)}
        title={`Delete ${toDelete?.name ?? "parameter"}?`}
      >
        {`Anything that reads ${parameterReference(scope, toDelete?.name ?? "")} will get no value.`}
      </ConfirmModal>
    </div>
  );
};

export default ParametersTable;
