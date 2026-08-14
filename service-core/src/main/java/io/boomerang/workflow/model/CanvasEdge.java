package io.boomerang.workflow.model;

import org.bson.types.ObjectId;

/*
 *   {
    "edges": [
      {
        "id": "3-4",
        "source": "3",
        "target": "4",
        "type": "decision",
        "data": {
          "decisionCondition": "bob",
          "executionCondition": "success"
        }
      }
    ]
  }
 */
public class CanvasEdge {
  
  String id = new ObjectId().toString();
  String source;
  String target;
  String type;
  CanvasEdgeData data;
  
  public String getId() {
    return id;
  }
  public void setId(String id) {
    this.id = id;
  }
  public String getSource() {
    return source;
  }
  public void setSource(String source) {
    this.source = source;
  }
  public String getTarget() {
    return target;
  }
  public void setTarget(String target) {
    this.target = target;
  }
  public String getType() {
    return type;
  }
  public void setType(String type) {
    this.type = type;
  }
  public CanvasEdgeData getData() {
    return data;
  }
  public void setData(CanvasEdgeData data) {
    this.data = data;
  } 
}
