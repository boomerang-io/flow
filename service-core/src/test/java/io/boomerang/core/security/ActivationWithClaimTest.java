package io.boomerang.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import io.boomerang.core.TokenService;
import io.boomerang.core.enums.UserType;
import io.boomerang.core.repository.UserRepository;
import jakarta.servlet.Filter;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The first admin of an empty install with an admin claim configured (name {@code groups}, value
 * {@code flow-admins}) and the forwarded token not trusted. A sign-in whose claim is read, the
 * verified id_token of the webapp, is decided by the claim alone. A sign-in whose claim cannot be
 * read (a forwarded email, or a forwarded token that is not trusted even when it names the admin
 * group) makes the first admin only by the activation code. Its own context and database.
 */
@SpringBootTest
class ActivationWithClaimTest {

  private static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:7.0"));
  private static final String CODE = "activation-claim-test-code-0123456789ab";

  static {
    MONGO.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", () -> MONGO.getReplicaSetUrl("boomerang"));
    registry.add("flow.mongo.collection.prefix", () -> "flowactivationclaim");
    registry.add("flow.encrypt.secret", () -> "test-encrypt-secret");
    registry.add("flow.encrypt.salt", () -> "test-encrypt-salt");
    registry.add("flow.events.sink.enabled", () -> "false");
    registry.add("flow.watcher.enabled", () -> "false");
    registry.add("flow.activation-code", () -> CODE);
    registry.add("flow.security.admin-claim.name", () -> "groups");
    registry.add("flow.security.admin-claim.values", () -> "flow-admins");
    registry.add("flow.security.admin-claim.trust-forwarded-token", () -> "false");
  }

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private TokenService tokenService;

  @Autowired
  @Qualifier("springSecurityFilterChain")
  private Filter springSecurityFilterChain;

  @MockitoBean private OidcTokenVerifier oidcTokenVerifier;

  private MockMvc mockMvc;

  @BeforeEach
  void emptyInstall() {
    SecurityContextHolder.clearContext();
    userRepository.deleteAll();
    ReflectionTestUtils.setField(tokenService, "sessionMintReuseWindow", Duration.ZERO);
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
  }

  // --- the claim is read: it decides, with no code ------------------------------------------------

  @Test
  void aVerifiedTokenNamingTheAdminGroupMakesTheFirstAdminWithoutACode() throws Exception {
    verified(List.of("flow-admins"));

    exchange().andExpect(status().isOk());

    assertThat(userRepository.findByEmail("founder@example.test").getType()).isEqualTo(UserType.admin);
  }

  @Test
  void aVerifiedTokenWithoutTheAdminGroupMakesAPlainUserNotAnAdmin() throws Exception {
    verified(List.of("staff"));

    exchange().andExpect(status().isOk());

    assertThat(userRepository.findByEmail("founder@example.test").getType()).isEqualTo(UserType.user);
  }

  // --- the claim cannot be read: the activation code decides ----------------------------------------

  @Test
  void aForwardedEmailWithoutTheCodeCreatesNobody() throws Exception {
    profile(emailOnly()).andExpect(status().isLocked());
    activate(emailOnly(), "{\"otc\":\"wrong\"}").andExpect(status().isBadRequest());

    assertThat(userRepository.count()).isZero();
  }

  @Test
  void aForwardedEmailWithTheRightCodeMakesTheFirstAdmin() throws Exception {
    activate(emailOnly(), "{\"otc\":\"" + CODE + "\"}").andExpect(status().isOk());

    assertThat(userRepository.findByEmail("founder@example.test").getType()).isEqualTo(UserType.admin);
  }

  @Test
  void anUntrustedForwardedTokenNamingTheAdminGroupIsNotAdminWithoutTheCode() throws Exception {
    profile(untrustedToken()).andExpect(status().isLocked());
    activate(untrustedToken(), "{\"otc\":\"wrong\"}").andExpect(status().isBadRequest());

    assertThat(userRepository.count()).isZero();
  }

  @Test
  void anUntrustedForwardedTokenWithTheRightCodeMakesTheFirstAdmin() throws Exception {
    activate(untrustedToken(), "{\"otc\":\"" + CODE + "\"}").andExpect(status().isOk());

    assertThat(userRepository.findByEmail("founder@example.test").getType()).isEqualTo(UserType.admin);
  }

  private void verified(List<String> groups) {
    org.mockito.Mockito.when(oidcTokenVerifier.verify("id-token", "nonce"))
        .thenReturn(
            new JWTClaimsSet.Builder()
                .subject("idp-1")
                .claim("email", "founder@example.test")
                .claim("groups", groups)
                .build());
  }

  private static HttpHeaders emailOnly() {
    HttpHeaders headers = new HttpHeaders();
    headers.add("x-forwarded-email", "founder@example.test");
    return headers;
  }

  private static HttpHeaders untrustedToken() {
    String jwt =
        new PlainJWT(
                new JWTClaimsSet.Builder()
                    .claim("email", "founder@example.test")
                    .claim("groups", List.of("flow-admins"))
                    .build())
            .serialize();
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.AUTHORIZATION, "Bearer " + jwt);
    return headers;
  }

  // The profile route only asks; the activate route carries the code.
  private ResultActions profile(HttpHeaders identity) throws Exception {
    SecurityContextHolder.clearContext();
    return mockMvc.perform(get("/api/v2/profile").servletPath("/api/v2/profile").headers(identity));
  }

  private ResultActions activate(HttpHeaders identity, String body) throws Exception {
    SecurityContextHolder.clearContext();
    return mockMvc.perform(
        put("/api/v2/activate")
            .servletPath("/api/v2/activate")
            .headers(identity)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private ResultActions exchange() throws Exception {
    SecurityContextHolder.clearContext();
    return mockMvc.perform(
        post("/internal/v1/auth/exchange")
            .servletPath("/internal/v1/auth/exchange")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"idToken\":\"id-token\",\"nonce\":\"nonce\"}"));
  }
}
