import { IsValidConnection, useReactFlow } from "@xyflow/react";
import { EdgeExecutionCondition, NodeType } from "Constants";
import type { WorkflowEdge, WorkflowNode } from "Types";

// A finally task is a task whose one incoming link comes from End with the finally condition.
export function isFinallyEdge(edge: WorkflowEdge) {
  return edge.data?.executionCondition === EdgeExecutionCondition.Finally;
}

export function isFinallyNode(edges: Array<WorkflowEdge>, nodeId: string) {
  return edges.some((edge) => edge.target === nodeId && isFinallyEdge(edge));
}

// Validator for the output port of every node but End: no self-links, and nothing links into a
// finally task. See StartNode.tsx for why this is typed via `IsValidConnection`.
export function useIsValidConnection(): IsValidConnection {
  const reactFlowInstance = useReactFlow<WorkflowNode, WorkflowEdge>();
  return (connection) =>
    connection.source !== connection.target && !isFinallyNode(reactFlowInstance.getEdges(), connection.target);
}

// Validator for End's output port: only a task that has no incoming link at all can become a
// finally task.
export function useIsValidFinallyConnection(): IsValidConnection {
  const reactFlowInstance = useReactFlow<WorkflowNode, WorkflowEdge>();
  return (connection) => {
    const target = reactFlowInstance.getNodes().find((node) => node.id === connection.target);
    if (!target || target.type === NodeType.Start || target.type === NodeType.End) {
      return false;
    }
    return !reactFlowInstance.getEdges().some((edge) => edge.target === connection.target);
  };
}
