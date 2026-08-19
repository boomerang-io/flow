import React, { useState } from "react";
import { DataTable, Pagination, DataTableSkeleton } from "@carbon/react";
import { Checkmark, Close } from "@carbon/react/icons";
import { Error } from "@boomerang-io/carbon-addons-boomerang-react";
import EmptyState from "Components/EmptyState";
import { InputType, PASSWORD_CONSTANT } from "Constants";
import { DataDrivenInput } from "Types";
import CreateEditParametersModal from "../CreateEditParametersModal";
import ActionsMenu from "./ActionsMenu";
import styles from "./parametersTable.module.scss";

const DEFAULT_PAGE_SIZE = 10;
const PAGE_SIZES = [DEFAULT_PAGE_SIZE, 25, 50];
const headers = [
  {
    header: "Label",
    key: "label",
    sortable: true,
  },
  {
    header: "Name",
    key: "name",
    sortable: true,
  },
  {
    header: "Description",
    key: "description",
    sortable: true,
  },
  {
    header: "Value",
    key: "value",
    sortable: true,
  },
  {
    header: "Secured",
    key: "secured",
    sortable: true,
  },
  {
    header: "",
    key: "actions",
  },
];

interface ParametersTableProps {
  parameters: Array<DataDrivenInput>;
  isLoading: boolean;
  isSubmitting: boolean;
  errorLoading: boolean;
  errorSubmitting: boolean;
  handleDelete: (parameter: DataDrivenInput) => Promise<void>;
  handleSubmit: (isEdit: boolean, parameter: DataDrivenInput, closeModal: () => void) => Promise<void>;
}

const ParametersTable: React.FC<ParametersTableProps> = ({
  parameters,
  errorSubmitting,
  errorLoading,
  isLoading,
  isSubmitting,
  handleDelete,
  handleSubmit,
}) => {
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);

  const handlePaginationChange = ({ page, pageSize }: { page: number; pageSize: number }) => {
    setPage(page);
    setPageSize(pageSize);
  };

  const renderCell = (propertyId: number, cellIndex: number, value: any) => {
    const parameter = parameters[propertyId]!;
    const column = headers[cellIndex];
    switch (column.key) {
      case "value":
        const determineValue = value
          ? parameter && parameter.type === InputType.Password
            ? PASSWORD_CONSTANT
            : value
          : "---";
        return <p className={styles.tableTextarea}>{determineValue}</p>;
      case "secured":
        return parameter && parameter.type === InputType.Password ? (
          <Checkmark size={32} alt="secured" className={`${styles.tableSecured} ${styles.secured}`} />
        ) : (
          <Close size={32} alt="unsecured" className={`${styles.tableSecured} ${styles.unsecured}`} />
        );
      case "actions":
        return (
          <ActionsMenu
            parameter={parameter}
            parameters={parameters}
            isSubmitting={isSubmitting}
            errorSubmitting={errorSubmitting}
            handleDelete={handleDelete}
            handleSubmit={handleSubmit}
          />
        );
      default:
        return value || "---";
    }
  };

  const { TableContainer, Table, TableHead, TableRow, TableBody, TableCell, TableHeader } = DataTable;
  const totalItems = parameters.length;
  const tableData = parameters.map((p, index) => ({ ...p, id: index.toString() }));

  return (
    <>
      <div className={styles.tableContainer}>
        <div className={styles.tableActions}>
          {!isLoading && !errorLoading && (
            <CreateEditParametersModal
              parameters={parameters}
              handleSubmit={handleSubmit}
              isSubmitting={isSubmitting}
              error={errorSubmitting}
            />
          )}
        </div>
        {isLoading ? (
          <DataTableSkeleton />
        ) : errorLoading ? (
          <Error />
        ) : totalItems > 0 ? (
          <>
            <DataTable
              rows={tableData}
              headers={headers}
              render={({ rows, headers, getHeaderProps }: { rows: any; headers: any; getHeaderProps: any }) => (
                <TableContainer>
                  <Table isSortable>
                    <TableHead>
                      <TableRow className={styles.tableHeadRow}>
                        {headers.map((header: any, key: any) => (
                          <TableHeader
                            key={`mode-table-key-${key}`}
                            {...getHeaderProps({
                              className: `${styles.tableHeadHeader} ${styles[header.key]}`,
                              header,
                              isSortable: header.sortable,
                            })}
                          >
                            {header.header}
                          </TableHeader>
                        ))}
                      </TableRow>
                    </TableHead>
                    <TableBody className={styles.tableBody}>
                      {rows.map((row: any) => {
                        return (
                          <TableRow key={row.id}>
                            {row.cells.map((cell: any, cellIndex: number) => (
                              <TableCell key={cell.id}>
                                <div className={styles.tableCell}>{renderCell(row.id, cellIndex, cell.value)}</div>
                              </TableCell>
                            ))}
                          </TableRow>
                        );
                      })}
                    </TableBody>
                  </Table>
                </TableContainer>
              )}
            />
            <Pagination
              onChange={handlePaginationChange}
              page={page}
              pageSize={pageSize}
              pageSizes={PAGE_SIZES}
              totalItems={totalItems}
            />
          </>
        ) : (
          <>
            <EmptyState title="No parameters found" message={null} />
          </>
        )}
      </div>
    </>
  );
};

export default ParametersTable;
