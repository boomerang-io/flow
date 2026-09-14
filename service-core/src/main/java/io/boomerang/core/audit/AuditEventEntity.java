package io.boomerang.core.audit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One flat document per audited attempt (SUCCESS, FAILED, or DENIED), written by {@link
 * AuditEventWriter}. Records who did what to which resource with what outcome; the payload carries
 * request discriminators (ids, short summaries) — never content.
 *
 * <p>Envelope fields follow the CloudEvents shape ({@code type}/{@code source}/{@code
 * time}/{@code subject}) so an event can be exported without reshaping; the flat
 * actor/resource/outcome fields are what the query indexes serve.
 *
 * <p>Indexes are owned by the loader ({@code _0042__AuditEventRestructure};
 * auto-index-creation is off): a TTL on {@code createdAt} driven by the {@code audit.retentionDays}
 * setting, {@code time} descending for the default listing, and the {@code (workspaceId, time)},
 * {@code (actorId, time)}, {@code (resourceType, resourceId, time)} compounds. The collection is
 * insert-only — no single-field indexes on low-cardinality fields (action, outcome); those
 * filters ride the time index.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
@Document(collection = "#{@mongoConfiguration.fullCollectionName('audit')}")
public class AuditEventEntity {

  @Id private String id;

  /** Event type identifier, versioned so consumers can key on the shape. */
  private String type = "io.boomerang.flow.audit.v1";

  private String source = "flow.audit";

  /** When the audited attempt happened. */
  private Date time;

  /** The affected resource id — the CloudEvents subject. */
  private String subject;

  /** TTL anchor; distinct from {@code time} so retention keys on the write, never the claim. */
  private Date createdAt = new Date();

  /** Token principal; "anonymous" for a tokenless HTTP caller; "system" for system work. */
  private String actorId;

  private String actorName;

  /** Token class or actor kind: session, user, key, global, service, agent, workflow, system. */
  private String actorType;

  /** Owning workspace, or null for instance-scoped events (tokens, settings). */
  private String workspaceId;

  /** {@link AuditAction} name. */
  private String action;

  private String resourceType;
  private String resourceId;
  private String resourceName;

  /** {@link AuditOutcome} name. */
  private String outcome;

  /** {@link AuditLevel} name the site declared. */
  private String level;

  /** Request discriminators and short summaries only — never content. */
  private Map<String, Object> payload = new HashMap<>();
}
