package io.boomerang.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jwt.JWTClaimsSet;
import io.boomerang.core.TokenService;
import io.boomerang.core.UserService;
import io.boomerang.core.enums.UserType;
import io.boomerang.core.repository.UserRepository;
import jakarta.servlet.Filter;
import java.time.Duration;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The first admin of an empty install, through the real filter chain and stores, with no admin
 * claim configured: only the one-time code creates them, on the proxy route (PUT /api/v2/activate)
 * and the webapp's verified-id_token exchange; a wrong code or none creates nobody; and the
 * bootstrap token works throughout without counting as a person, and can name the first admin
 * itself. Its own context and database,
 * because it needs an install with no users.
 */
@SpringBootTest
class ActivationTest {

  private static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:7.0"));
  private static final String CODE = "activation-test-code-0123456789abcdef";
  private static final String BOOTSTRAP = "bfg_activation-test-bootstrap-token-0123456789abcdef";

  static {
    MONGO.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", () -> MONGO.getReplicaSetUrl("boomerang"));
    registry.add("flow.mongo.collection.prefix", () -> "flowactivation");
    registry.add("flow.encrypt.secret", () -> "test-encrypt-secret");
    registry.add("flow.encrypt.salt", () -> "test-encrypt-salt");
    registry.add("flow.events.sink.enabled", () -> "false");
    registry.add("flow.watcher.enabled", () -> "false");
    registry.add("flow.activation-code", () -> CODE);
    registry.add("flow.security.bootstrap-token", () -> BOOTSTRAP);
  }

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private UserService userService;
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
    // A session minted for an earlier test's person must not answer this test's sign-in.
    ReflectionTestUtils.setField(tokenService, "sessionMintReuseWindow", Duration.ZERO);
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
    org.mockito.Mockito.when(oidcTokenVerifier.verify("id-token", "nonce"))
        .thenReturn(
            new JWTClaimsSet.Builder().subject("idp-1").claim("email", "founder@example.test").build());
  }

  // --- the bootstrap token ----------------------------------------------------------------------

  @Test
  void theBootstrapTokenWorksBeforeAnyoneActivatesAndNeverActivatesAnything() throws Exception {
    mockMvc
        .perform(get("/api/v2/features").header(HttpHeaders.AUTHORIZATION, "Bearer " + BOOTSTRAP))
        .andExpect(status().isOk());
    SecurityContextHolder.clearContext();

    assertThat(userService.isActivated()).isFalse();
    assertThat(userRepository.count()).isZero();

    // Activation is still required of the first person.
    mockMvc
        .perform(
            get("/api/v2/profile")
                .servletPath("/api/v2/profile")
                .header("x-forwarded-email", "founder@example.test"))
        .andExpect(status().isLocked());
    assertThat(userRepository.count()).isZero();
  }

  @Test
  void theBootstrapTokenCanNameTheFirstAdminWithNoActivationCode() throws Exception {
    // 1. The bootstrap token adds the person to a workspace by email: a user record now exists, so
    // the install counts as activated and the code is never asked for.
    mockMvc
        .perform(
            post("/api/v2/workspace")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + BOOTSTRAP)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"founders\",\"displayName\":\"Founders\","
                        + "\"members\":[{\"email\":\"founder@example.test\",\"role\":\"owner\"}]}"))
        .andExpect(status().is2xxSuccessful());
    SecurityContextHolder.clearContext();
    assertThat(userService.isActivated()).isTrue();

    // 2. Their first sign-in needs no code and activates their record, as a plain user.
    mockMvc
        .perform(
            get("/api/v2/profile")
                .servletPath("/api/v2/profile")
                .header("x-forwarded-email", "founder@example.test"))
        .andExpect(status().isOk());
    SecurityContextHolder.clearContext();
    String id = userRepository.findByEmail("founder@example.test").getId();
    assertThat(userRepository.findById(id).orElseThrow().getType()).isEqualTo(UserType.user);

    // 3. The bootstrap token makes them an admin.
    mockMvc
        .perform(
            patch("/api/v2/user/" + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + BOOTSTRAP)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"admin\"}"))
        .andExpect(status().isOk());
    assertThat(userRepository.findById(id).orElseThrow().getType()).isEqualTo(UserType.admin);
  }

  // --- behind a proxy ---------------------------------------------------------------------------

  @Test
  void behindAProxyAnEmptyInstallAnswersLockedSoTheActivationScreenShows() throws Exception {
    mockMvc
        .perform(
            get("/api/v2/profile")
                .servletPath("/api/v2/profile")
                .header("x-forwarded-email", "founder@example.test"))
        .andExpect(status().isLocked());

    assertThat(userRepository.count()).isZero();
  }

  @Test
  void behindAProxyAWrongOrMissingCodeCreatesNobody() throws Exception {
    activate("{\"otc\":\"wrong\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.reason").value("AUTH_ACTIVATION_CODE_INVALID"))
        .andExpect(jsonPath("$.message").value("The activation code is not correct."));
    activate("{}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.reason").value("AUTH_ACTIVATION_CODE_INVALID"));
    activate("")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.reason").value("AUTH_ACTIVATION_CODE_INVALID"));

    assertThat(userRepository.count()).isZero();
  }

  @Test
  void behindAProxyTheRightCodeCreatesTheFirstAdminAndLaterPeopleSignInAsUsers() throws Exception {
    activate("{\"otc\":\"" + CODE + "\"}").andExpect(status().isOk());

    assertThat(userRepository.findByEmail("founder@example.test").getType()).isEqualTo(UserType.admin);

    SecurityContextHolder.clearContext();
    mockMvc
        .perform(
            get("/api/v2/profile")
                .servletPath("/api/v2/profile")
                .header("x-forwarded-email", "second@example.test"))
        .andExpect(status().isOk());
    assertThat(userRepository.findByEmail("second@example.test").getType()).isEqualTo(UserType.user);
  }

  @Test
  void onceActivatedTheRightCodeIsRefusedOnTheActivationRoute() throws Exception {
    activate("{\"otc\":\"" + CODE + "\"}").andExpect(status().isOk());

    activate("{\"otc\":\"" + CODE + "\"}").andExpect(status().isBadRequest());
  }

  // --- the webapp's OpenID Connect exchange ------------------------------------------------------

  @Test
  void theExchangeWithNoCodeAsksForActivationAndCreatesNobody() throws Exception {
    exchange("{\"idToken\":\"id-token\",\"nonce\":\"nonce\"}")
        .andExpect(status().isLocked())
        .andExpect(jsonPath("$.reason").value("AUTH_ACTIVATION_REQUIRED"));

    assertThat(userRepository.count()).isZero();
  }

  @Test
  void theExchangeWithAWrongCodeCreatesNobody() throws Exception {
    exchange("{\"idToken\":\"id-token\",\"nonce\":\"nonce\",\"otc\":\"wrong\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.reason").value("AUTH_ACTIVATION_CODE_INVALID"));

    assertThat(userRepository.count()).isZero();
  }

  @Test
  void theExchangeWithTheRightCodeCreatesTheFirstAdminAndSetsTheSession() throws Exception {
    exchange("{\"idToken\":\"id-token\",\"nonce\":\"nonce\",\"otc\":\"" + CODE + "\"}")
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("flow_session=bfs_")));

    assertThat(userRepository.findByEmail("founder@example.test").getType()).isEqualTo(UserType.admin);
  }

  @Test
  void onceActivatedTheExchangeSignsInWithoutACode() throws Exception {
    exchange("{\"idToken\":\"id-token\",\"nonce\":\"nonce\",\"otc\":\"" + CODE + "\"}")
        .andExpect(status().isOk());

    exchange("{\"idToken\":\"id-token\",\"nonce\":\"nonce\"}").andExpect(status().isOk());
    assertThat(userRepository.count()).isEqualTo(1);
  }

  private org.springframework.test.web.servlet.ResultActions activate(String body) throws Exception {
    SecurityContextHolder.clearContext();
    return mockMvc.perform(
        put("/api/v2/activate")
            .servletPath("/api/v2/activate")
            .header("x-forwarded-email", "founder@example.test")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private org.springframework.test.web.servlet.ResultActions exchange(String body) throws Exception {
    SecurityContextHolder.clearContext();
    return mockMvc.perform(
        post("/internal/v1/auth/exchange")
            .servletPath("/internal/v1/auth/exchange")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }
}
