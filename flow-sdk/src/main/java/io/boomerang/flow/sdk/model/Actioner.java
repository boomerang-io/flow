package io.boomerang.flow.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/** One decision recorded on an action: who decided, when, which way, and their comments. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Actioner(
    String approverId,
    String approverEmail,
    String approverName,
    Boolean approved,
    String comments,
    Instant date) {}
