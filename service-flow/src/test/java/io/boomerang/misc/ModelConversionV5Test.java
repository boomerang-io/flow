package io.boomerang.misc;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.boomerang.integrations.data.entity.RevisionEntity;
import io.boomerang.workflow.model.projectstormv5.WorkflowRevision;
import io.boomerang.util.ModelConverterV5;

public class ModelConversionV5Test {

  @Test
  public void testGithubWorkflowConversionFromMongo() throws IOException {


    File resource = new ClassPathResource("scenarios/github/github-mongo.json").getFile();

    String json = new String(Files.readAllBytes(resource.toPath()));
    ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    RevisionEntity entity = objectMapper.readValue(json, RevisionEntity.class);

    WorkflowRevision convertedRevision = ModelConverterV5.convertToRestModel(entity);

    String restString = objectMapper.writeValueAsString(convertedRevision);
    assertNotNull(restString);
    System.out.println(restString);

  }

  @Test
  public void testGithubWorkflowConversion() throws IOException {


    File resource = new ClassPathResource("scenarios/github/github-rest.json").getFile();

    String json = new String(Files.readAllBytes(resource.toPath()));
    ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    WorkflowRevision revision = objectMapper.readValue(json, WorkflowRevision.class);
    RevisionEntity convertedRevision = ModelConverterV5.convertToEntityModel(revision);

    String restString = objectMapper.writeValueAsString(convertedRevision);
    assertNotNull(restString);
    System.out.println(restString);

  }

  @Test
  public void testBrokenLink() throws IOException {
    File resource = new ClassPathResource("scenarios/decision-defaultValues.json").getFile();
    String json = new String(Files.readAllBytes(resource.toPath()));
    ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    WorkflowRevision revision = objectMapper.readValue(json, WorkflowRevision.class);
    RevisionEntity convertedRevision = ModelConverterV5.convertToEntityModel(revision);
    assertNotNull(convertedRevision);

    WorkflowRevision rest = ModelConverterV5.convertToRestModel(convertedRevision);

    String restString = objectMapper.writeValueAsString(rest);
    assertNotNull(rest);
    System.out.println(restString);
  }


  @Test
  public void testDecisionValuesSaving() throws IOException {
    File resource = new ClassPathResource("scenarios/decision-values.json").getFile();
    String json = new String(Files.readAllBytes(resource.toPath()));
    ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    WorkflowRevision revision = objectMapper.readValue(json, WorkflowRevision.class);
    RevisionEntity convertedRevision = ModelConverterV5.convertToEntityModel(revision);
    assertNotNull(convertedRevision);

    WorkflowRevision rest = ModelConverterV5.convertToRestModel(convertedRevision);

    String restString = objectMapper.writeValueAsString(rest);
    assertNotNull(rest);
    System.out.println(restString);
  }

  @Test
  public void testDecisionRestToEntity() throws IOException {
    File resource = new ClassPathResource("scenarios/decision-workflow.json").getFile();
    String json = new String(Files.readAllBytes(resource.toPath()));
    ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    WorkflowRevision revision = objectMapper.readValue(json, WorkflowRevision.class);
    RevisionEntity convertedRevision = ModelConverterV5.convertToEntityModel(revision);
    assertNotNull(convertedRevision);


    String jsonString = objectMapper.writeValueAsString(convertedRevision);
    assertNotNull(jsonString);

    System.out.println("*****************");
    System.out.println("Before");
    System.out.println("*****************");
    System.out.println(json);

    System.out.println("*****************");
    System.out.println("After");
    System.out.println("*****************");
    System.out.println(jsonString);
  }

  @Test
  public void testRestToEntity() throws IOException {
    File resource = new ClassPathResource("scenarios/blank-workflow.json").getFile();
    String json = new String(Files.readAllBytes(resource.toPath()));
    ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    WorkflowRevision revision = objectMapper.readValue(json, WorkflowRevision.class);
    RevisionEntity convertedRevision = ModelConverterV5.convertToEntityModel(revision);
    assertNotNull(convertedRevision);


    String jsonString = objectMapper.writeValueAsString(convertedRevision);
    assertNotNull(jsonString);

    System.out.println("*****************");
    System.out.println("Before");
    System.out.println("*****************");
    System.out.println(json);

    System.out.println("*****************");
    System.out.println("After");
    System.out.println("*****************");
    System.out.println(jsonString);
  }

}
