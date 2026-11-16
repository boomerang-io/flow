import { Position } from "@xyflow/react";
import { describe, expect, it } from "vitest";
import { autoLayoutElements } from "Features/Reactflow/Reactflow";
import { NodeType } from "Constants";
import type { WorkflowEdge, WorkflowNode } from "Types";

/*
 * Auto-layout hands back new node objects: React Flow tracks nodes by reference, so moving a node
 * in place leaves it where it was on the canvas.
 */
const node = (id: string, type: string, x: number, y: number) =>
  ({ id, type, position: { x, y }, data: { name: id } }) as unknown as WorkflowNode;
const edge = (source: string, target: string) =>
  ({ id: `${source}-${target}`, source, target }) as unknown as WorkflowEdge;

describe("autoLayoutElements", () => {
  it("lays a chain out left to right as new node objects, leaving the input untouched", () => {
    const nodes = [
      node("start", NodeType.Start, 900, 900),
      node("a", NodeType.Template, 0, 500),
      node("b", NodeType.Template, 300, 0),
      node("end", NodeType.End, 0, 0),
    ];
    const edges = [edge("start", "a"), edge("a", "b"), edge("b", "end")];

    const { nodes: laidOut } = autoLayoutElements(nodes, edges);

    laidOut.forEach((laid, i) => {
      expect(laid).not.toBe(nodes[i]);
      expect(laid.targetPosition).toBe(Position.Left);
      expect(laid.sourcePosition).toBe(Position.Right);
    });
    expect(nodes[0].position).toEqual({ x: 900, y: 900 });
    const xs = laidOut.map((laid) => laid.position.x);
    expect(xs).toEqual([...xs].sort((left, right) => left - right));
    expect(new Set(laidOut.map((laid) => laid.position.y)).size).toBe(1);
  });
});
