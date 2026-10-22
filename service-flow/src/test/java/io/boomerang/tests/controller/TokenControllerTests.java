package io.boomerang.tests.controller;


import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import io.boomerang.workflow.TokenController;
import io.boomerang.misc.FlowTests;
import io.boomerang.workflow.model.CreateTeamTokenRequest;
import io.boomerang.workflow.model.CreateTokenRequest;
import io.boomerang.workflow.model.Token;
import io.boomerang.workflow.model.TokenResponse;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@WithMockUser(roles = {"admin"})
@WithUserDetails("mdroy@us.ibm.com")
public class TokenControllerTests extends FlowTests {

  @Autowired
  private TokenController tokenController;

  @Test
  public void testCreatingGlobalToken() {

    CreateTokenRequest request = new CreateTokenRequest();
    request.setDescription("Sample token");
    request.setExpiryDate(new Date());

    Token response = tokenController.createNewGlobalToken(request);
    Assertions.assertNotNull(response.getTokenValue());

    String tokenValue = response.getTokenValue();
    System.out.println(tokenValue);

    List<Token> tokens = tokenController.getAllGlobalTokens();

    Assertions.assertEquals(1, tokens.size());

    Token token = tokens.get(0);
    Assertions.assertEquals(token.getDescription(), "Sample token");
  }

  @Test
  public void testCreatingTeamToken() {

    CreateTeamTokenRequest request = new CreateTeamTokenRequest();
    request.setDescription("Team token");
    request.setExpiryDate(new Date());
    request.setTeamId("12345");

    Token response = tokenController.createNewTeamToken(request);
    Assertions.assertNotNull(response.getTokenValue());

    String tokenValue = response.getTokenValue();
    System.out.println(tokenValue);

    List<Token> tokens = tokenController.getTokensForTeam("12345");

    Assertions.assertEquals(1, tokens.size());

    Token token = tokens.get(0);
    Assertions.assertEquals(token.getDescription(), "Team token");
  }
}
