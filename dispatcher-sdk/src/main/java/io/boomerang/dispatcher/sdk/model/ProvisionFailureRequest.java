package io.boomerang.dispatcher.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Why a claimed workflow run could not be provisioned. The engine shows it on the run and decides
 * whether to retry.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProvisionFailureRequest(String message) {}
