import React from "react";
import {
  FlowTeam,
  FlowUser,
  PaginatedWorkflowResponse,
  Task,
  WorkflowCanvas,
  WorkflowEngineModeType,
  WorkflowRun,
  WorkflowTemplate,
} from "Types";

export function createContext<ContextType>() {
  const context = React.createContext<ContextType | undefined>(undefined);
  function useContext() {
    const contextValue = React.useContext(context);
    if (!contextValue) throw new Error("useContext must be inside a Provider with a value");
    return contextValue;
  }
  return [useContext, context.Provider] as const;
}

export const [useAppContext, AppContextProvider] = createContext<AppContext>();

type AppContext = {
  communityUrl: string;
  isTutorialActive: boolean;
  setIsTutorialActive: (isActive: boolean) => void;
  teams: FlowTeam[] | null; // TODO - check if we need this
  user: FlowUser;
  name: string;
  workflowTemplates: Array<WorkflowTemplate>;
};

interface WorkflowContext {
  mode: WorkflowEngineModeType;
  tasks: Record<string, Array<Task>>;
}

export const [useWorkflowContext, WorkflowProvider] = createContext<WorkflowContext>();

interface RunContext {
  workflow: WorkflowCanvas;
  workflowRun: WorkflowRun;
}
export const [useRunContext, RunContextProvider] = createContext<RunContext>();

interface EditorContext {
  availableParameters: Array<string>;
  revisionDispatch?: Function;
  revisionState: WorkflowCanvas;
  workflowsQueryData: PaginatedWorkflowResponse;
}

export const [useEditorContext, EditorContextProvider] = createContext<EditorContext>();

interface TeamContext {
  team: FlowTeam;
}

export const [useTeamContext, TeamContextProvider] = createContext<TeamContext>();
