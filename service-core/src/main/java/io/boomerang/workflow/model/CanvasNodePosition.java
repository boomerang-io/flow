package io.boomerang.workflow.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class CanvasNodePosition {
  
  @JsonProperty("x")
  private Number x;
  @JsonProperty("y")
  private Number y;
  
  public Number getX() {
    return x;
  }
  public void setX(Number x) {
    this.x = x;
  }
  public Number getY() {
    return y;
  }
  public void setY(Number y) {
    this.y = y;
  }
}
