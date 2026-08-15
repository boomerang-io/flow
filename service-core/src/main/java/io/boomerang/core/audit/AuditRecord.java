package io.boomerang.core.audit;

import java.util.Date;
import java.util.Map;
import lombok.Value;

/*
 * Minimal read-model projection of an AuditEntity.
 *
 * Exposed by AuditQueryService so that callers outside io.boomerang.core (e.g.
 * io.boomerang.workspace.InsightsService) can query audit data without importing the
 * AuditRepository/AuditEntity persistence types directly.
 */
@Value
public class AuditRecord {
  String id;
  AuditScope scope;
  String selfRef;
  String selfName;
  String parent;
  Date creationDate;
  Map<String, String> data;
}
