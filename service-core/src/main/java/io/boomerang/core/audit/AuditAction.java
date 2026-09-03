package io.boomerang.core.audit;

/**
 * What the actor did. Each action maps to an OCSF class and activity for SIEM export — the mapping
 * is derived at export time (never stored on the event) so a correction applies retroactively.
 *
 * <p>OCSF references: API Activity (class_uid 6003) for resource CRUD, Authentication (class_uid
 * 3002) for token/auth actions. Activity ids follow the OCSF CRUD convention (1=Create, 2=Read,
 * 3=Update, 4=Delete, 99=Other).
 */
public enum AuditAction {
  CREATE,
  READ,
  UPDATE,
  DELETE,
  DUPLICATE,
  SUBMIT,
  EXPORT,
  IMPORT,
  TOKEN_CREATE,
  TOKEN_REVOKE;

  /** OCSF activity_id: 1=Create, 2=Read, 3=Update, 4=Delete, 99=Other. */
  public int ocsfActivityId() {
    return switch (this) {
      case CREATE, DUPLICATE, TOKEN_CREATE -> 1;
      case READ, EXPORT -> 2;
      case UPDATE, IMPORT -> 3;
      case DELETE, TOKEN_REVOKE -> 4;
      default -> 99;
    };
  }

  /** OCSF class_uid: 3002 Authentication for token actions, else 6003 API Activity. */
  public int ocsfClassUid() {
    return switch (this) {
      case TOKEN_CREATE, TOKEN_REVOKE -> 3002;
      default -> 6003;
    };
  }
}
