package io.boomerang.workflow.model;

import io.boomerang.common.enums.TaskType;
import org.bson.types.ObjectId;

/*
* Utilizes the ReactFlow Node model https://reactflow.dev/docs/api/nodes/node-options/ with the custom data
* "nodes": [
     {
       "id": "start",
       "position": {
         "x": 200,
         "y": 200
       },
       "data": {
         "label": "start",
         "params": [
           {
             "name": "outputToProcess",
             "value": "$(tasks.invert-colours.results.output)"
           }
         ]
       },
       "type": "start"
     }
   ],
*/
public class CanvasNode {

  String id = new ObjectId().toString();

  CanvasNodePosition position;

  CanvasNodeData data;

  TaskType type;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public CanvasNodePosition getPosition() {
    return position;
  }

  public void setPosition(CanvasNodePosition position) {
    this.position = position;
  }

  public CanvasNodeData getData() {
    return data;
  }

  public void setData(CanvasNodeData data) {
    this.data = data;
  }

  public TaskType getType() {
    return type;
  }

  public void setType(TaskType type) {
    this.type = type;
  }
}
