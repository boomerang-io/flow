package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * The template a workflow runs by reference: on the API the template's {@code name} and, to pin
 * one, its {@code version}; stored, the template's id. A workflow carrying one is a link: its
 * tasks and params are the template's revision, read-only, and a submit runs that revision in the
 * link's own workspace. No {@code version} runs the template's latest revision at each submit.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowTemplateRef {
  @JsonIgnore private String ref;
  private String name;
  private Integer version;
}
