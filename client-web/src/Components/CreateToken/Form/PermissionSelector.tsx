import React from "react";
import {
  Accordion,
  AccordionItem,
  Checkbox,
  Dropdown,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableHeader,
  TableRow,
} from "@carbon/react";
import { Loading } from "@boomerang-io/carbon-addons-boomerang-react";
import queryString from "query-string";
import { useQuery } from "react-query";
import { resolver, serviceUrl } from "Config/servicesConfig";
import styles from "./permissionSelector.module.scss";

/*
 * Server-driven permission catalog - resources/actions/role presets always come from
 * GET /token/catalog so this picker can never offer (or silently drop) something the
 * server doesn't actually enforce.
 */
interface TokenCatalog {
  resources: string[];
  actions: string[];
  rolePresets: Record<string, string[]>;
}

export interface PermissionSelection {
  role?: string;
  permissions?: string[];
}

interface PermissionSelectorProps {
  scope: "global" | "workspace";
  principal?: string | null;
  onChange: (selection: PermissionSelection) => void;
}

type PermissionGrid = Record<string, Record<string, boolean>>;

function capitalize(value: string): string {
  return value ? value.charAt(0).toUpperCase() + value.slice(1) : value;
}

function buildGrid(resources: string[], actions: string[], permissions: string[]): PermissionGrid {
  const grid: PermissionGrid = {};
  resources.forEach((resource) => {
    grid[resource] = {};
    actions.forEach((action) => {
      grid[resource][action] =
        permissions.includes(`${resource}/${action}`) ||
        permissions.includes(`${resource}/**`) ||
        permissions.includes(`**/${action}`) ||
        permissions.includes("**/**");
    });
  });
  return grid;
}

function gridToPermissions(resources: string[], actions: string[], grid: PermissionGrid): string[] {
  const permissions: string[] = [];
  resources.forEach((resource) => {
    actions.forEach((action) => {
      if (grid[resource]?.[action]) {
        permissions.push(`${resource}/${action}`);
      }
    });
  });
  return permissions;
}

/*
 * A role preset dropdown, selected by default, with a "Customise permissions" disclosure
 * that reveals a resource x action checkbox grid pre-checked to that role's actions. Flow's
 * action vocabulary is a closed 4-value set applied uniformly across every resource, so this
 * is a grid (row/column "all" toggles) rather than a per-resource verb builder.
 */
function PermissionSelector({ scope, principal, onChange }: PermissionSelectorProps) {
  const catalogUrl = serviceUrl.getTokenCatalog({
    query: queryString.stringify({ scope, principal: principal ?? undefined }),
  });
  const catalogQuery = useQuery<TokenCatalog>({
    queryKey: catalogUrl,
    queryFn: resolver.query(catalogUrl),
  });
  const catalog = catalogQuery.data;

  // "" (rather than undefined) so the role Dropdown is controlled from the very first render -
  // switching an uncontrolled Downshift prop to controlled once the catalog resolves triggers a
  // console warning.
  const [selectedRole, setSelectedRole] = React.useState<string>("");
  const [isCustomising, setIsCustomising] = React.useState(false);
  const [grid, setGrid] = React.useState<PermissionGrid>({});

  // Default the preset once the catalog resolves.
  React.useEffect(() => {
    if (catalog && !selectedRole) {
      const [firstRole] = Object.keys(catalog.rolePresets);
      if (firstRole) {
        setSelectedRole(firstRole);
      }
    }
  }, [catalog, selectedRole]);

  // Re-seed the grid to the selected preset whenever the grid becomes visible or the
  // preset changes while it's open - diverging edits are only kept until the preset changes.
  React.useEffect(() => {
    if (catalog && isCustomising && selectedRole) {
      setGrid(buildGrid(catalog.resources, catalog.actions, catalog.rolePresets[selectedRole] ?? []));
    }
  }, [catalog, isCustomising, selectedRole]);

  // Emit exactly one of role/permissions to the caller. `onChange` is intentionally excluded
  // from the dependency list - the caller passes a fresh inline handler on every render, and
  // depending on it here would re-fire this effect (via the caller re-rendering in response
  // to its own call) without changing what gets emitted.
  React.useEffect(() => {
    if (!catalog) {
      return;
    }
    if (isCustomising) {
      onChange({ permissions: gridToPermissions(catalog.resources, catalog.actions, grid) });
    } else if (selectedRole) {
      onChange({ role: selectedRole });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [catalog, isCustomising, selectedRole, grid]);

  if (catalogQuery.isLoading) {
    return <Loading withOverlay={false} small />;
  }

  if (catalogQuery.isError || !catalog) {
    return <p className={styles.error}>Unable to load the permission catalog. Try again.</p>;
  }

  const { resources, actions, rolePresets } = catalog;

  const setCell = (resource: string, action: string, checked: boolean) => {
    setGrid((current) => ({ ...current, [resource]: { ...current[resource], [action]: checked } }));
  };

  const setRow = (resource: string, checked: boolean) => {
    const row: Record<string, boolean> = {};
    actions.forEach((action) => {
      row[action] = checked;
    });
    setGrid((current) => ({ ...current, [resource]: row }));
  };

  const setColumn = (action: string, checked: boolean) => {
    setGrid((current) => {
      const next: PermissionGrid = { ...current };
      resources.forEach((resource) => {
        next[resource] = { ...next[resource], [action]: checked };
      });
      return next;
    });
  };

  return (
    <div className={styles.container}>
      <Dropdown
        id="token-role"
        titleText="Role"
        label="Select a role"
        items={Object.keys(rolePresets)}
        itemToString={(item: string | null) => (item ? capitalize(item) : "")}
        selectedItem={selectedRole}
        onChange={(data: { selectedItem: string | null }) => {
          if (data.selectedItem) {
            setSelectedRole(data.selectedItem);
          }
        }}
      />
      <Accordion className={styles.accordion}>
        <AccordionItem
          title="Customise permissions"
          open={isCustomising}
          onHeadingClick={({ isOpen }: { isOpen: boolean }) => setIsCustomising(isOpen)}
        >
          <TableContainer className={styles.tableContainer}>
            <Table size="sm">
              <TableHead>
                <TableRow>
                  <TableHeader>Resource</TableHeader>
                  {actions.map((action) => {
                    const columnChecked = resources.every((resource) => grid[resource]?.[action]);
                    const columnIndeterminate =
                      !columnChecked && resources.some((resource) => grid[resource]?.[action]);
                    return (
                      <TableHeader key={action}>
                        <Checkbox
                          id={`token-permission-column-${action}`}
                          labelText={capitalize(action)}
                          checked={columnChecked}
                          indeterminate={columnIndeterminate}
                          onChange={(_evt, { checked }: { checked: boolean }) => setColumn(action, checked)}
                        />
                      </TableHeader>
                    );
                  })}
                  <TableHeader>All</TableHeader>
                </TableRow>
              </TableHead>
              <TableBody>
                {resources.map((resource) => {
                  const rowChecked = actions.every((action) => grid[resource]?.[action]);
                  const rowIndeterminate = !rowChecked && actions.some((action) => grid[resource]?.[action]);
                  return (
                    <TableRow key={resource}>
                      <TableCell>{capitalize(resource)}</TableCell>
                      {actions.map((action) => (
                        <TableCell key={action}>
                          <Checkbox
                            id={`token-permission-${resource}-${action}`}
                            labelText={`${capitalize(resource)} ${action}`}
                            hideLabel
                            checked={Boolean(grid[resource]?.[action])}
                            onChange={(_evt, { checked }: { checked: boolean }) => setCell(resource, action, checked)}
                          />
                        </TableCell>
                      ))}
                      <TableCell>
                        <Checkbox
                          id={`token-permission-row-all-${resource}`}
                          labelText={`All ${capitalize(resource)} actions`}
                          hideLabel
                          checked={rowChecked}
                          indeterminate={rowIndeterminate}
                          onChange={(_evt, { checked }: { checked: boolean }) => setRow(resource, checked)}
                        />
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </TableContainer>
        </AccordionItem>
      </Accordion>
    </div>
  );
}

export default PermissionSelector;
