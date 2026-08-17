import React from "react";
import { Accordion, AccordionItem, Checkbox, Layer, OverflowMenu, Search, SkeletonText } from "@carbon/react";
import { Bee, ViewOff, Recommend, SettingsAdjust } from "@carbon/react/icons";
import {
  CheckboxList,
  FeatureSideNav as SideNav,
  FeatureSideNavLink as SideNavLink,
  FeatureSideNavLinks as SideNavLinks,
  TooltipHover,
} from "@boomerang-io/carbon-addons-boomerang-react";
import sortBy from "lodash/sortBy";
import { matchSorter } from "match-sorter";
import { useHistory } from "react-router-dom";
import { taskIcons } from "Utils/taskIcons";
import { TaskTemplateStatus } from "Constants";
import { appLink } from "Config/appConfig";
import { FlowTeam, Task } from "Types";
import AddTaskTemplate from "./AddTaskTemplate";
import styles from "./sideInfo.module.scss";

const DESCRIPTION = "Create and import tasks to add to the Workflow Editor task list";

const taskFilterElemList = taskIcons.map((TaskIcon) => ({
  id: TaskIcon.name,
  labelText: (
    <div className={styles.checkboxOption}>
      <TaskIcon.Icon /> <p>{TaskIcon.name}</p>{" "}
    </div>
  ),
}));

interface SideInfoProps {
  team?: FlowTeam;
  isLoading?: boolean;
  tasks: Array<Task>;
  getTaskTemplatesUrl: string;
}

const SideInfo: React.FC<SideInfoProps> = ({ team, isLoading, tasks, getTaskTemplatesUrl }) => {
  const history = useHistory();
  const [activeFilters, setActiveFilters] = React.useState<Array<string>>([]);
  const [openCategories, setOpenCategories] = React.useState(false);
  const [searchQuery, setSearchQuery] = React.useState("");
  const [showArchived, setShowArchived] = React.useState(false);
  const [showVerified, setShowVerified] = React.useState(false);

  const handleOnSearchInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const searchQuery = e.target.value;
    setSearchQuery(searchQuery);
  };

  const handleCheckboxListChange = (_: boolean, label: string) => {
    let filtersState: string[] = [...activeFilters];
    let newFilters = [];
    let hasFilter = Boolean(filtersState.find((filter) => filter === label));
    if (hasFilter) newFilters = filtersState.filter((filter) => filter !== label);
    else newFilters = filtersState.concat(label);
    setActiveFilters(newFilters);
  };

  const handleClearFilters = () => {
    setShowArchived(false);
    setShowVerified(false);
    setActiveFilters([]);
  };

  if (isLoading) {
    return (
      <SideNav className={styles.container} border="right">
        <h1 className={styles.title}>{team ? "Team " : ""}Task manager</h1>
        <p className={styles.description}>{DESCRIPTION}</p>
        <div style={{ padding: "1.5rem 1rem" }}>
          <SkeletonText />
          <SkeletonText />
          <SkeletonText />
          <SkeletonText />
          <SkeletonText />
        </div>
      </SideNav>
    );
  }

  // List of categories
  let categories = tasks
    ?.reduce((acc: string[], task: Task) => {
      const newCategory = !acc.find((category) => task.category === category);
      if (newCategory) acc.push(task.category);
      return acc;
    }, [])
    .sort();

  // Apply various filters
  const tasksFilteredByVerified = showVerified ? tasks?.filter((task) => task.verified === true) : tasks;

  const tasksFilteredByStatus = showArchived
    ? tasksFilteredByVerified
    : tasksFilteredByVerified?.filter((task) => task.status === TaskTemplateStatus.Active);

  const tasksFilteredByType =
    activeFilters.length > 0
      ? tasksFilteredByStatus?.filter((task) => activeFilters.includes(task.icon))
      : tasksFilteredByStatus;

  const filteredTaskTemplates = matchSorter(tasksFilteredByType, searchQuery, { keys: ["category", "displayName"] });

  const tasksByCategory = categories?.map((category) => ({
    name: category,
    tasks: sortBy(filteredTaskTemplates.filter((task) => task.category === category).sort(), "displayName"),
  }));

  const distinctTaskNames = tasks?.map((task) => task.name);

  return (
    <SideNav className={styles.container} border="right">
      <h1 className={styles.title}>{team ? "Team " : ""}Task manager</h1>
      <p className={styles.description}>{DESCRIPTION}</p>
      {tasks && (
        <div className={styles.tasksContainer}>
          <div className={styles.addTaskContainer}>
            <p className={styles.existingTasks}>{`Existing Tasks (${tasks?.length})`}</p>
            <AddTaskTemplate
              taskNames={distinctTaskNames}
              history={history}
              getTaskTemplatesUrl={getTaskTemplatesUrl}
            />
          </div>
          <Layer className={styles.tools}>
            <Search
              data-testid="task-templates-search"
              id="task-templates-search"
              size="sm"
              labelText="Search for a task"
              onChange={handleOnSearchInputChange}
              placeholder="Search for a task"
              value={searchQuery}
            />
            <OverflowMenu
              ariaLabel="Filter"
              renderIcon={SettingsAdjust}
              style={{
                backgroundColor: showVerified || showArchived || activeFilters.length > 0 ? "#3DDBD9" : "initial",
                borderRadius: "0.25rem",
              }}
              flipped={true}
              menuOptionsClass={styles.filters}
              size="sm"
            >
              <section className={styles.filterHeader}>
                <p className={styles.filterTitle}>Filters</p>
                <button className={styles.resetFilter} onClick={handleClearFilters}>
                  Reset filters
                </button>
              </section>
              <section className={styles.filter}>
                <Checkbox
                  id="archived-tasks"
                  labelText="Show Archived Tasks"
                  checked={showArchived}
                  onChange={() => setShowArchived(!showArchived)}
                />
                <Checkbox
                  id="verified-tasks"
                  labelText={
                    <div className={styles.checkboxOption}>
                      <Recommend fill="#0072C3" style={{ willChange: "auto" }} /> <p>Verified Tasks</p>
                    </div>
                  }
                  checked={showVerified}
                  onChange={() => setShowVerified(!showVerified)}
                />
              </section>
              <section className={styles.filter}>
                <p className={styles.sectionTitle}>Filter by Task Type</p>
                <CheckboxList
                  selectedItems={activeFilters}
                  options={taskFilterElemList}
                  onChange={(checked: boolean, label: string) => handleCheckboxListChange(checked, label)}
                />
              </section>
            </OverflowMenu>
          </Layer>
          <div className={styles.tasksInfo}>
            <p className={styles.info}>{`Showing ${tasks.length} tasks`}</p>
            <button className={styles.expandCollapse} onClick={() => setOpenCategories(!openCategories)}>
              {openCategories ? "Collapse all" : "Expand all"}
            </button>
          </div>
        </div>
      )}
      {tasks && (
        <SideNavLinks>
          <Accordion>
            {tasksByCategory?.map((category, index) => {
              return (
                <AccordionItem
                  className={styles.taskCategory}
                  title={`${category.name} (${category.tasks.length})`}
                  open={openCategories}
                  key={`${category.name}${index}`}
                >
                  {category.tasks.length > 0 ? (
                    category.tasks.map((task) => (
                      //@ts-ignore
                      <TaskCard key={task.name} task={task} team={team ?? null} />
                    ))
                  ) : (
                    <EmptyTask key={`${category.name}-empty`} />
                  )}
                </AccordionItem>
              );
            })}
          </Accordion>
        </SideNavLinks>
      )}
    </SideNav>
  );
};

interface TaskCardProps {
  task: Task;
  team?: FlowTeam | null;
}
const TaskCard: React.FC<TaskCardProps> = (props) => {
  const { task, team } = props;
  const TaskIcon = taskIcons.find((icon) => icon.name === task.icon);
  const taskIsActive = task.status === TaskTemplateStatus.Active;

  return (
    <SideNavLink
      to={
        team
          ? appLink.manageTasksEdit({
              team: team.name,
              name: task.name,
              version: task.version.toString(),
            })
          : appLink.adminTasksDetail({
              name: task.name,
              version: task.version.toString(),
            })
      }
      icon={TaskIcon ? TaskIcon.Icon : Bee}
    >
      <div className={styles.task}>
        <p>{task.displayName}</p>
        {(task.verified || !taskIsActive) && (
          <div className={styles.iconContainer}>
            {!taskIsActive && (
              <TooltipHover direction="top" tooltipText="Archived Task">
                <ViewOff fill="#4d5358" />
              </TooltipHover>
            )}
            {task.verified && (
              <TooltipHover
                direction="top"
                tooltipText={
                  <div className={styles.tooltipContainer}>
                    <strong>Verified</strong>
                    <p style={{ marginTop: "0.5rem" }}>
                      This task has been fully tested and verified right out of the box.
                    </p>
                  </div>
                }
              >
                <Recommend />
              </TooltipHover>
            )}
          </div>
        )}
      </div>
    </SideNavLink>
  );
};

//TODO make the below a part of the empty state of the component in the add-ons
const EmptyTask: React.FC = () => {
  return (
    <div className={`cds--bmrg-feature-sidenav-link`}>
      <div className={`cds--bmrg-feature-sidenav-link-content`}>
        <div className={styles.task}>
          <p>There are no tasks for this category. This may be due to filters applied.</p>
        </div>
      </div>
    </div>
  );
};

export default SideInfo;
