package io.boomerang.controller;

import java.util.Date;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.boomerang.model.CreateTeamTokenRequest;
import io.boomerang.model.CreateTokenRequest;
import io.boomerang.model.Token;
import io.boomerang.model.TokenResponse;
import io.boomerang.mongo.service.FlowTokenService;
import io.boomerang.security.service.UserValidationService;

@RestController
@RequestMapping("/workflow")
public class TokenController {

  @Autowired
  private FlowTokenService tokenService;

  @Autowired
  private UserValidationService userValidationService;

  @GetMapping(value = "/tokens/team/{teamId}")
  public List<Token> getTokensForTeam(@PathVariable String teamId) {
    return tokenService.findAllTeamTokens(teamId);
  }

  @GetMapping(value = "/tokens/global-tokens")
  public List<Token> getAllGlobalTokens() {
    userValidationService.validateUserAdminOrOperator();
    return tokenService.findAllGlobalTokens();
  }

  @DeleteMapping(value = "/token/{tokenId}")
  public void deleteTeamProperty(@PathVariable String tokenId) {
    tokenService.deleteToken(tokenId);
  }

  @PostMapping(value = "/global-token")
  public TokenResponse createNewGlobalToken(
      @RequestBody CreateTokenRequest request) {
    userValidationService.validateUserAdminOrOperator();
    Date expiryDate = request.getExpiryDate();
    String description = request.getDescription();

    return tokenService.createSystemToken(expiryDate, description);
  }

  @PostMapping(value = "/team-token")
  public TokenResponse createNewTeamToken(
      @RequestBody CreateTeamTokenRequest request) {
    Date expiryDate = request.getExpiryDate();
    String description = request.getDescription();
    String teamId = request.getTeamId();

    return tokenService.createTeamToken(teamId, expiryDate, description);
  }
}
