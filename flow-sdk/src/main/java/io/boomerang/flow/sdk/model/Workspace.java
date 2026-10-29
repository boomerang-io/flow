package io.boomerang.flow.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * A workspace as the server answers it: its names, status and type, its labels, and {@code
 * externalRef}, the id an embedding product gave the record this workspace mirrors. Members,
 * quotas, parameters, approver groups and workflows are read through their own routes.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Workspace(
    String id,
    String name,
    String displayName,
    String externalRef,
    WorkspaceStatus status,
    WorkspaceType type,
    Instant creationDate,
    Map<String, String> labels) {}
