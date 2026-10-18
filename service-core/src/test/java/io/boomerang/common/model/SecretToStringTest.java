package io.boomerang.common.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.WorkflowRevisionEntity;
import io.boomerang.common.enums.ParamType;
import io.boomerang.common.util.DataAdapterUtil;
import io.boomerang.common.util.ParameterUtil;
import java.util.LinkedList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * toString is the safety net behind every log line that dumps an object: a secret's value never
 * prints, a plain value still does so a debug line stays useful.
 */
class SecretToStringTest {

  private static final String SECRET = "s3cr3t-pa55w0rd";

  @Test
  void aSecretRunParamPrintsTheMarkerAndAPlainOneItsValue() {
    String secret = new RunParam("dbPassword", SECRET, ParamType.secret).toString();
    assertFalse(secret.contains(SECRET), secret);
    assertTrue(secret.contains("name=dbPassword"), secret);
    assertTrue(secret.contains("type=secret"), secret);
    assertTrue(secret.contains(DataAdapterUtil.REDACTED), secret);

    String plain = new RunParam("greeting", "hello", ParamType.string).toString();
    assertTrue(plain.contains("value=hello"), plain);
  }

  @Test
  void aPasswordFieldPrintsTheMarkerForItsDefaultAndValue() {
    AbstractParam password = new AbstractParam();
    password.setName("apiKey");
    password.setType("password");
    password.setDefaultValue(SECRET);
    password.setValue(SECRET + "-value");

    String shown = password.toString();
    assertFalse(shown.contains(SECRET), shown);
    assertTrue(shown.contains("name=apiKey"), shown);
    assertTrue(shown.contains("defaultValue=" + DataAdapterUtil.REDACTED), shown);
    assertTrue(shown.contains("value=" + DataAdapterUtil.REDACTED), shown);

    AbstractParam text = new AbstractParam();
    text.setName("greeting");
    text.setType("text");
    text.setDefaultValue("hello");
    assertTrue(text.toString().contains("defaultValue=hello"), text.toString());
  }

  @Test
  void anEnvVarPrintsItsNameOnly() {
    String shown = new TaskEnvVar("DB_PASSWORD", SECRET).toString();
    assertFalse(shown.contains(SECRET), shown);
    assertTrue(shown.contains("DB_PASSWORD"), shown);
  }

  @Test
  void aRunSpecLeavesOutWhatIsFilledInAtClaim() {
    TaskRunSpec spec = new TaskRunSpec();
    spec.setImage("alpine:3.19");
    spec.setScript("echo " + SECRET);
    spec.setCommand(List.of("login", SECRET));
    spec.setArguments(List.of("--token", SECRET));
    spec.setEnvs(List.of(new TaskEnvVar("TOKEN", SECRET)));

    String shown = spec.toString();
    assertFalse(shown.contains(SECRET), shown);
    assertTrue(shown.contains("alpine:3.19"), shown);
    assertTrue(shown.contains("TOKEN"), shown);
  }

  @Test
  void anObjectHoldingParamsPrintsNoSecret() {
    AbstractParam password = new AbstractParam();
    password.setName("dbPassword");
    password.setType("password");
    password.setDefaultValue(SECRET);
    WorkflowTask node = new WorkflowTask();
    node.setName("work");
    node.setParams(new LinkedList<>(List.of(new RunParam("apiKey", SECRET, ParamType.secret))));
    WorkflowRevisionEntity revision = new WorkflowRevisionEntity();
    revision.setParams(new LinkedList<>(List.of(password)));
    revision.setTasks(new LinkedList<>(List.of(node)));

    String shown = revision.toString();
    assertFalse(shown.contains(SECRET), shown);
    assertTrue(shown.contains("dbPassword") && shown.contains("apiKey"), shown);
  }

  @Test
  void aLogLineCarriesParamNamesOnly() {
    assertEquals(
        "dbPassword, greeting",
        ParameterUtil.runParamNames(
            List.of(
                new RunParam("dbPassword", SECRET, ParamType.secret),
                new RunParam("greeting", "hello"))));
    assertEquals("", ParameterUtil.runParamNames(null));
    AbstractParam password = new AbstractParam();
    password.setName("apiKey");
    password.setDefaultValue(SECRET);
    assertEquals("apiKey", ParameterUtil.abstractParamNames(List.of(password)));
  }
}
