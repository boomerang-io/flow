package io.boomerang.tests;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.cloudevents.CloudEvent;
import io.cloudevents.v1.AttributesImpl;
import io.cloudevents.v1.http.Unmarshallers;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@WithMockUser(roles = {"admin"})
@WithUserDetails("mdroy@us.ibm.com")
public class TestCloudEvent {

  @Test
  public void testCloudEvent() {
    Map<String, Object> headers = new HashMap<>();
    String natsMessage = "{\n" + "  \"data\" : {\n" + "    \"name\" : \"tyson\"\n" + "  },\n"
        + "  \"id\" : \"145d1c2c-5ea0-4ba8-a354-aa3bad8209c9\",\n"
        + "  \"source\" : \"http://wdc2.cloud.boomerangplatform.net/listener/webhook/wfe\",\n"
        + "  \"specversion\" : \"1.0\",\n" + "  \"type\" : \"io.boomerang.eventing.wfe\",\n"
        + "  \"subject\" : \"/5f7f8cf69a7d401d9e584c90/6010b41bbadf2e7743e03324/bar\",\n"
        + "  \"time\" : 1.6117074537076776E9,\n" + "  \"status\" : \"success\"\n" + "}";
    try {

      ZonedDateTime now = ZonedDateTime.now();
      String formattedDate =
          DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS").format(now) + 'Z';
      JsonNode timeNode = new TextNode(formattedDate);
      JsonNode payload = getJsonNode(natsMessage);
      ((ObjectNode) payload).set("time", timeNode);
      CloudEvent<AttributesImpl, JsonNode> event = Unmarshallers.structured(JsonNode.class)
          .withHeaders(() -> headers).withPayload(() -> payload.toString()).unmarshal();
      System.out.println(event.getAttributes().getTime().get());
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  private JsonNode getJsonNode(String jsonString)
      throws IOException, JsonParseException, JsonProcessingException {
    ObjectMapper mapper = new ObjectMapper();
    JsonFactory factory = mapper.getFactory();
    JsonParser parser = factory.createParser(jsonString);
    JsonNode actualObj = mapper.readTree(parser);
    return actualObj;
  }

}
