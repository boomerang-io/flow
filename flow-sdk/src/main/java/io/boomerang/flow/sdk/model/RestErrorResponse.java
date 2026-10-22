package io.boomerang.flow.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * The server's error body: a code and reason from its closed error set, the message for people,
 * and the HTTP status as text ({@code 400 BAD_REQUEST}). {@code cause} is set only when the
 * server is configured to include one.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RestErrorResponse(
    Instant timestamp, int code, String reason, String message, String status, String cause) {}
