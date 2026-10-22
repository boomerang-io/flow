package io.boomerang.flow.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Where a retried run started from: present only on a run a scoped retry created, with the task
 * names when the scope is {@code nodes}. The tasks before that point were carried over from the
 * origin run rather than run again.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RetryFrom(RetryScope scope, List<String> nodes) {}
