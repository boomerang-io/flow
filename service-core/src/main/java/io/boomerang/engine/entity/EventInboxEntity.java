package io.boomerang.engine.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.engine.enums.InboxStatus;
import java.util.Date;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Dedup ledger for inbound events. The id is "&lt;scope&gt;:&lt;eventId&gt;" and the insert is
 * the dedup gate: a DuplicateKeyException means transport redelivery and the event must not be
 * re-applied.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Document(collection = "#{@mongoConfiguration.fullCollectionName('events_inbox')}")
public class EventInboxEntity {

  @Id private String id;
  private String topic;
  private RunStatus requestedStatus;
  private InboxStatus status = InboxStatus.received;
  private Date receivedAt;
  private Date processedAt;
}
