import { Workflow, WorkflowCanvasState } from "Types";

//@ts-nocheck
export const RevisionActionTypes = {
  UpdateNodes: "UPDATE_NODES",
  UpdateEdges: "UPDATE_EDGES",
  UpdateConfig: "UPDATE_CONFIG",
  Reset: "RESET",
  Set: "SET",
  UpdateNodeConfig: "UPDATE_NODE_CONFIG",
  UpdateNodeTaskVersion: "UPDATE_NODE_TASK_VERSION",
  UpdateNotes: "UPDATE_NOTES",
} as const;

type RevisionActionType = typeof RevisionActionTypes[keyof typeof RevisionActionTypes];

export function revisionReducer(state: WorkflowCanvasState, action: { data: any; type: RevisionActionType }) {
  console.log({ action });
  switch (action.type) {
    case RevisionActionTypes.UpdateEdges: {
      state.hasUnsavedUpdates = true;
      state.edges = action.data;
      return state;
    }
    case RevisionActionTypes.UpdateNodes: {
      state.hasUnsavedUpdates = true;
      state.nodes = action.data;
      return state;
    }
    case RevisionActionTypes.UpdateNotes: {
      const { markdown } = action.data;
      state.markdown = markdown;
      state.hasUnsavedUpdates = true;
      return state;
    }
    case RevisionActionTypes.Set: {
      return action.data;
    }
    case RevisionActionTypes.UpdateConfig: {
      const { parameters } = action.data;
      state.config = parameters;
      return state;
    }
    case RevisionActionTypes.Reset: {
      return action.data;
    }
    default:
      throw new Error();
  }
}

export function initRevisionReducerState(workflow: Workflow): WorkflowCanvasState {
  return { ...workflow, hasUnsavedUpdates: false };
}
