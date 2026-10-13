package io.boomerang.dispatcher.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * The task run ids a dispatcher is still working on. The engine renews the lease of each one the
 * dispatcher still owns and ignores the rest.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HeartbeatRequest(List<String> ids) {}
