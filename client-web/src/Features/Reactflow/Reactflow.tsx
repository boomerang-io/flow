import React from "react";
import dagre from "@dagrejs/dagre";
import {
  ReactFlow,
  Background,
  Connection,
  ControlButton,
  Controls,
  Edge,
  EdgeTypes,
  NodeTypes,
  Position,
  ReactFlowProvider,
  XYPosition,
  addEdge,
  useEdgesState,
  useNodesState,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { WorkflowProvider } from "State/context";
import { EdgeExecutionCondition, NodeType, WorkflowEngineMode } from "Constants";
import {
  NodeTypeType,
  Task,
  WorkflowEdge,
  WorkflowEngineModeType,
  WorkflowNode,
  WorkflowReactFlowInstance,
} from "Types";
import * as GraphComps from "./components";
import "./styles.scss";

export const markerTypes: { [K in NodeTypeType]: string } = {
  approval: "task-marker",
  acquirelock: "task-marker",
  custom: "task-marker",
  decision: "decision-marker",
  end: "task-marker",
  eventwait: "task-marker",
  generic: "task-marker",
  manual: "task-marker",
  releaselock: "task-marker",
  runscheduledworkflow: "task-marker",
  runworkflow: "task-marker",
  script: "task-marker",
  setwfproperty: "task-marker",
  setwfstatus: "task-marker",
  template: "task-marker",
  start: "task-marker",
  sleep: "task-marker",
};

// The value type is taken from xyflow's own `EdgeTypes`/`NodeTypes` aliases (via indexed
// access) rather than the bare `EdgeProps`/`NodeProps` used by each component. xyflow types
// `EdgeTypes`/`NodeTypes` as `ComponentType<EdgeProps & { data: any; type: any }>` precisely so
// that components typed narrowly against a specific `Edge`/`Node` subtype (like
// `WorkflowEdgeProps`/`WorkflowNodeProps`) can be registered here - the intersection widens
// `data`/`type` back out at this one boundary. Reusing the library's own alias keeps that
// widening scoped to where xyflow itself put it, instead of us re-deriving it locally.
const edgeTypes: { [K in NodeTypeType]: EdgeTypes[string] } = {
  acquirelock: GraphComps.TemplateEdge,
  approval: GraphComps.TemplateEdge,
  custom: GraphComps.TemplateEdge,
  decision: GraphComps.DecisionEdge,
  end: GraphComps.TemplateEdge,
  eventwait: GraphComps.TemplateEdge,
  generic: GraphComps.TemplateEdge,
  manual: GraphComps.TemplateEdge,
  releaselock: GraphComps.TemplateEdge,
  runscheduledworkflow: GraphComps.TemplateEdge,
  runworkflow: GraphComps.TemplateEdge,
  script: GraphComps.TemplateEdge,
  setwfproperty: GraphComps.TemplateEdge,
  setwfstatus: GraphComps.TemplateEdge,
  start: GraphComps.StartEdge,
  template: GraphComps.TemplateEdge,
  sleep: GraphComps.TemplateEdge,
};

const nodeTypes: { [K in NodeTypeType]: NodeTypes[string] } = {
  acquirelock: GraphComps.TemplateNode,
  approval: GraphComps.ApprovalNode,
  custom: GraphComps.CustomTaskNode,
  decision: GraphComps.DecisionNode,
  end: GraphComps.EndNode,
  eventwait: GraphComps.TemplateNode,
  generic: GraphComps.TemplateNode,
  manual: GraphComps.TemplateNode,
  releaselock: GraphComps.TemplateNode,
  runscheduledworkflow: GraphComps.RunScheduledWorkflowNode,
  runworkflow: GraphComps.RunWorkflowNode,
  script: GraphComps.ScriptNode,
  setwfproperty: GraphComps.TemplateNode,
  setwfstatus: GraphComps.TemplateNode,
  start: GraphComps.StartNode,
  template: GraphComps.TemplateNode,
  sleep: GraphComps.TemplateNode,
};

interface FlowDiagramProps {
  mode: WorkflowEngineModeType;
  nodes?: WorkflowNode[];
  edges?: WorkflowEdge[];
  reactFlowInstance: WorkflowReactFlowInstance | null;
  setReactFlowInstance?: React.Dispatch<React.SetStateAction<WorkflowReactFlowInstance | null>>;
  tasks: Record<string, Array<Task>>;
}

function FlowDiagram(props: FlowDiagramProps) {
  /**
   * Set up state and refs
   */
  const reactFlowWrapper = React.useRef<HTMLDivElement>(null);
  const { nodes: initNodes, edges: initEdges } = initElements(props.nodes, props.edges);
  const [nodes, setNodes, onNodesChange] = useNodesState<WorkflowNode>(initNodes ?? []);
  const [edges, setEdges, onEdgesChange] = useEdgesState<WorkflowEdge>(initEdges ?? []);
  const shouldFitGraph = React.useRef(false);

  React.useEffect(() => {
    if (shouldFitGraph.current) {
      props.reactFlowInstance?.fitView();
      shouldFitGraph.current = false;
    }
  });

  /**
   * Handle changes of nodes and eges
   */
  const onConnect = React.useCallback(
    (connection: Connection) =>
      setEdges((edges) => addEdge({ ...connection, ...getEdgeType(connection, nodes) }, edges)),
    [setEdges, nodes],
  );

  const onLayout = React.useCallback(() => {
    const { nodes: positionedNodes, edges: positionedEdges } = autoLayoutElements(nodes, edges);
    setNodes([...positionedNodes]);
    setEdges([...positionedEdges]);
    shouldFitGraph.current = true;
  }, [nodes, edges, setEdges, setNodes]);

  /**
   * Handle drag action w/ drag and drop
   */
  const onDragOver = React.useCallback((event: React.DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = "move";
  }, []);

  /**
   * Handle drop action w/ drag and drop
   */
  const onDrop = React.useCallback(
    (event: React.DragEvent<HTMLDivElement>) => {
      event.preventDefault();
      const taskString = event.dataTransfer.getData("application/reactflow") as string;
      const task = JSON.parse(taskString) as Task;

      // check if the dropped element is valid
      if (typeof task.type === "undefined" || !task) {
        return;
      }

      // v12 replaces `project()` with `screenToFlowPosition()`, which takes raw
      // client/screen coordinates directly and does the pane-bounds subtraction
      // internally - the manual `getBoundingClientRect()` offset this used to need is gone.
      // The -75/-25 offset is unrelated to that: it centers the dropped node under the
      // cursor (half the default node width/height) and is kept as-is.
      const position = props.reactFlowInstance?.screenToFlowPosition({
        x: event.clientX - 75,
        y: event.clientY - 25,
      }) as XYPosition;

      // TODO: clean this up - determines how to give the task template a unique name
      const numTaskRefInstances = nodes.reduce((accum, currentNode) => {
        if (currentNode.data.taskRef === task.name) {
          accum += 1;
        }
        return accum;
      }, 0);

      const taskName = numTaskRefInstances ? `${task.displayName} ${numTaskRefInstances + 1}` : task.displayName;

      const newNode: WorkflowNode = {
        id: taskName,
        type: task.type,
        position,
        data: {
          name: taskName,
          taskRef: task.name,
          taskVersion: task.version,
          upgradesAvailable: false,
          params: [],
          // Not set on drop (no results yet); v11's untyped `data: any` let this be omitted,
          // v12's stricter `Node<Data>` typing requires it since `WorkflowNodeData.results`
          // is a required field.
          results: [],
        },
      };

      setNodes((nds) => nds.concat(newNode));
    },
    [props.reactFlowInstance, nodes, setNodes],
  );

  const isEnabled = props.mode === WorkflowEngineMode.Edit;

  return (
    <div className="reactflow-container">
      <WorkflowProvider value={{ mode: props.mode, tasks: props.tasks }}>
        <ReactFlowProvider>
          <div className="reactflow-wrapper" data-mode={props.mode} ref={reactFlowWrapper}>
            {/*
              Explicit generic arguments here (rather than letting them get inferred from
              `nodes`/`edges`) because `onNodesChange`/`onEdgesChange` use `NodeType`/`EdgeType`
              contravariantly and `nodeTypes`/`edgeTypes` aren't generic at all (see the comment
              above their declarations) - left to infer on its own, the type checker falls back
              to the component's bare `Node`/`Edge` defaults and then rejects the
              `WorkflowNode`/`WorkflowEdge`-typed change handlers as a mismatch.
            */}
            <ReactFlow<WorkflowNode, WorkflowEdge>
              edges={edges}
              edgeTypes={edgeTypes}
              elementsSelectable={isEnabled}
              fitView={true}
              fitViewOptions={{ maxZoom: 1 }}
              nodeTypes={nodeTypes}
              nodes={nodes}
              nodesConnectable={isEnabled}
              nodesDraggable={isEnabled}
              onConnect={onConnect}
              onDrop={onDrop}
              onDragOver={onDragOver}
              onEdgesChange={onEdgesChange}
              onNodesChange={onNodesChange}
              onInit={props.setReactFlowInstance}
              proOptions={{ hideAttribution: true }}
            >
              <MarkerDefinition>
                <CustomEdgeArrow id={markerTypes.decision} />
                <CustomEdgeArrow id={markerTypes.template} />
              </MarkerDefinition>
              <Background />
              <Controls showInteractive={false}>
                {isEnabled ? (
                  <ControlButton onClick={onLayout} title="auto-layout">
                    <div>L</div>
                  </ControlButton>
                ) : null}
              </Controls>
            </ReactFlow>
          </div>
        </ReactFlowProvider>
      </WorkflowProvider>
    </div>
  );
}

interface CustomEdgeArrowProps {
  id: string;
}
function CustomEdgeArrow({ id }: CustomEdgeArrowProps) {
  return (
    <marker
      id={id}
      markerWidth="16"
      markerHeight="16"
      viewBox="-10 -10 20 20"
      markerUnits="strokeWidth"
      orient="auto-start-reverse"
      refX="0"
      refY="0"
    >
      <polyline strokeLinecap="round" strokeLinejoin="round" strokeWidth="1" points="-5,-4 0,0 -5,4 -5,-4"></polyline>
    </marker>
  );
}

interface MarkerDefinitionsProps {
  children: React.ReactNode;
}

export function MarkerDefinition({ children }: MarkerDefinitionsProps) {
  return (
    <svg>
      <defs>{children}</defs>
    </svg>
  );
}

// Determine if we should use auto-layout or not
function initElements(nodes: Array<WorkflowNode> = [], edges: Array<WorkflowEdge> = []) {
  let useAutoLayout = false;
  for (let node of nodes) {
    if (!node.position) {
      useAutoLayout = true;
      break;
    }
  }

  return useAutoLayout ? autoLayoutElements(nodes, edges) : { nodes, edges };
}

// Auto-layout via dagre
function autoLayoutElements(nodes: Array<WorkflowNode> = [], edges: Array<WorkflowEdge> = []) {
  const START_END_WIDTH = 144;
  const NODE_HEIGHT = 60;
  const NODE_WIDTH = 280;

  const direction = edges.length === 0 ? "TB" : "LR";
  const dagreGraph = new dagre.graphlib.Graph();
  dagreGraph.setDefaultEdgeLabel(() => ({}));
  dagreGraph.setGraph({ rankdir: direction, edgesep: 100 });

  nodes.forEach((node: WorkflowNode) => {
    if (node.type === NodeType.Start || node.type === NodeType.End) {
      dagreGraph.setNode(node.id, { width: START_END_WIDTH * 1.5, height: NODE_HEIGHT });
    } else {
      dagreGraph.setNode(node.id, { width: NODE_WIDTH * 1.5, height: NODE_HEIGHT });
    }
  });

  edges.forEach((edge: Edge) => {
    dagreGraph.setEdge(edge.source, edge.target);
  });

  dagre.layout(dagreGraph);

  nodes.forEach((node: WorkflowNode) => {
    const positionedNode = dagreGraph.node(node.id);
    node.targetPosition = Position.Left;
    node.sourcePosition = Position.Right;

    // We are shifting the dagre node position (anchor=center center) to the top left
    // so it matches the React Flow node anchor point (top left)
    if (node.type === NodeType.Start || node.type === NodeType.End) {
      node.position = {
        x: positionedNode.x - START_END_WIDTH / 2,
        y: positionedNode.y - NODE_HEIGHT / 2,
      };
    } else {
      node.position = {
        x: positionedNode.x - NODE_WIDTH / 2,
        y: positionedNode.y - NODE_HEIGHT / 2,
      };
    }

    // Do some additional adjustment if there are just two nodes
    // so the start and end aren't so close together and look weird
    if (nodes.length === 2) {
      if (node.type === NodeType.Start) {
        node.position.x = node.position.x - START_END_WIDTH * 2;
      }
      if (node.type === NodeType.End) {
        node.position.x = node.position.x + START_END_WIDTH * 2;
      }
    }

    return node;
  });

  return { nodes, edges };
}

function getEdgeType(connection: Connection, nodes: WorkflowNode[]): Pick<WorkflowEdge, "type" | "data"> {
  const { source } = connection;
  const node = nodes.find((node) => node.id === source) as WorkflowNode;
  return {
    type: node.type,
    data: { executionCondition: EdgeExecutionCondition.Always, decisionCondition: "" },
  };
}

export default FlowDiagram;
