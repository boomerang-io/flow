package io.boomerang.core.config;

import io.boomerang.common.entity.TaskRevisionEntity;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRevisionEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.entity.WorkflowScheduleEntity;
import io.boomerang.common.enums.ParamType;
import io.boomerang.common.model.RunParam;
import io.boomerang.core.SecretCipher;
import io.boomerang.workflow.entity.GlobalParamEntity;
import io.boomerang.workspace.entity.WorkspaceEntity;
import java.util.List;
import java.util.function.UnaryOperator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.springframework.data.mongodb.core.mapping.event.AbstractMongoEventListener;
import org.springframework.data.mongodb.core.mapping.event.AfterLoadEvent;
import org.springframework.data.mongodb.core.mapping.event.BeforeSaveEvent;
import org.springframework.stereotype.Component;

/**
 * Secret parameter values are encrypted at rest and plain in memory. The hook works on the BSON
 * document, never the entity: on save the document about to be written is encrypted, so the
 * caller's entity stays plaintext; on load the stored document is decrypted before it is mapped,
 * so every read - repository, template, find-and-modify - hands out plaintext. Nothing else in
 * Flow sees ciphertext.
 *
 * <p>A secret is recognised by the type stored beside its value:
 *
 * <ul>
 *   <li>global parameters, workspace {@code parameters[]}: {@code type == password} → {@code value}
 *   <li>task revision {@code spec.params[]}, workflow revision {@code params[]}: {@code type ==
 *       password} → {@code defaultValue}
 *   <li>workflow revision {@code tasks[].params[]}, workflow run, task run and schedule {@code
 *       params[]}: {@code type == secret} → {@code value}
 * </ul>
 *
 * A value already carrying the label is not encrypted again, and a value without it loads as it
 * is and is encrypted on its next save. Only string values are encrypted. Writes that bypass the
 * entity lifecycle - a Mongo {@code Update} - call {@link #sealRunParams} for the params they set.
 */
@Component
public class SecretValueMongoListener extends AbstractMongoEventListener<Object> {

  private static final Logger LOGGER = LogManager.getLogger();

  private static final String PASSWORD = "password";
  private static final String SECRET = ParamType.secret.name();

  private final SecretCipher cipher;

  public SecretValueMongoListener(SecretCipher cipher) {
    this.cipher = cipher;
  }

  @Override
  public void onBeforeSave(BeforeSaveEvent<Object> event) {
    if (event.getDocument() != null && event.getSource() != null) {
      apply(event.getSource().getClass(), event.getDocument(), cipher::encrypt);
    }
  }

  @Override
  public void onAfterLoad(AfterLoadEvent<Object> event) {
    if (event.getDocument() != null && event.getType() != null) {
      apply(event.getType(), event.getDocument(), this::decryptOrKeep);
    }
  }

  /**
   * Copies of the params with every secret value encrypted, for a write that sets params through a
   * Mongo {@code Update} and so never reaches {@link #onBeforeSave}. The given list is untouched.
   */
  public List<RunParam> sealRunParams(List<RunParam> params) {
    if (params == null) {
      return null;
    }
    return params.stream()
        .map(
            p -> {
              if (ParamType.secret.equals(p.getType()) && p.getValue() instanceof String value) {
                return new RunParam(p.getName(), cipher.encrypt(value), p.getType());
              }
              return p;
            })
        .toList();
  }

  private void apply(Class<?> type, Document document, UnaryOperator<String> transform) {
    if (GlobalParamEntity.class.isAssignableFrom(type)) {
      transformIf(document, PASSWORD, "value", transform);
    } else if (WorkspaceEntity.class.isAssignableFrom(type)) {
      eachParam(document.get("parameters"), PASSWORD, "value", transform);
    } else if (TaskRevisionEntity.class.isAssignableFrom(type)) {
      if (document.get("spec") instanceof Document spec) {
        eachParam(spec.get("params"), PASSWORD, "defaultValue", transform);
      }
    } else if (WorkflowRevisionEntity.class.isAssignableFrom(type)) {
      eachParam(document.get("params"), PASSWORD, "defaultValue", transform);
      if (document.get("tasks") instanceof List<?> tasks) {
        tasks.forEach(
            task -> {
              if (task instanceof Document taskDocument) {
                eachParam(taskDocument.get("params"), SECRET, "value", transform);
              }
            });
      }
    } else if (WorkflowRunEntity.class.isAssignableFrom(type)
        || TaskRunEntity.class.isAssignableFrom(type)
        || WorkflowScheduleEntity.class.isAssignableFrom(type)) {
      eachParam(document.get("params"), SECRET, "value", transform);
    }
  }

  private static void eachParam(
      Object params, String secretType, String field, UnaryOperator<String> transform) {
    if (params instanceof List<?> list) {
      list.forEach(
          param -> {
            if (param instanceof Document paramDocument) {
              transformIf(paramDocument, secretType, field, transform);
            }
          });
    }
  }

  private static void transformIf(
      Document param, String secretType, String field, UnaryOperator<String> transform) {
    if (secretType.equals(param.get("type")) && param.get(field) instanceof String value) {
      param.put(field, transform.apply(value));
    }
  }

  // A value that will not decrypt - a different key, or a tampered value - is kept as stored, so a
  // later save cannot overwrite it with nothing.
  private String decryptOrKeep(String value) {
    String plaintext = cipher.decrypt(value);
    if (plaintext == null) {
      LOGGER.error("A stored secret parameter value could not be decrypted; it is left as stored.");
      return value;
    }
    return plaintext;
  }
}
