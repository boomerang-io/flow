package io.boomerang.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import io.boomerang.core.SettingsService;
import io.boomerang.core.TokenService;
import io.boomerang.core.UserService;
import io.boomerang.core.entity.SettingEntity;
import io.boomerang.core.entity.UserEntity;
import io.boomerang.core.enums.UserStatus;
import io.boomerang.core.enums.UserType;
import io.boomerang.core.repository.UserRepository;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import jakarta.servlet.Filter;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Who a sign-in admits, through the real filter chain and real stores: the auto-create users setting
 * and the identity provider's admin claim, on the proxy-forwarded bearer JWT, the forwarded-email
 * header and the verified-id_token path of the sign-in exchange (the webapp-run provider and a
 * local IDPZero both arrive there). The context sets the claim to {@code groups} holding {@code
 * flow-admins} and trusts the proxy's token.
 */
class SignInModesTest extends AbstractEngineIntegrationTest {

  private static final String PROFILE = "/api/v2/profile";

  @Autowired private WebApplicationContext context;
  @Autowired private SettingsService settingsService;
  @Autowired private TokenService tokenService;
  @Autowired private UserService userService;
  @Autowired private UserRepository userRepository;

  @Autowired
  @Qualifier("springSecurityFilterChain")
  private Filter springSecurityFilterChain;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    SecurityContextHolder.clearContext();
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
    // The install has users, so no sign-in here is the first one.
    UserEntity anchor = new UserEntity();
    anchor.setEmail("anchor-" + UUID.randomUUID() + "@example.test");
    anchor.setName("anchor");
    userRepository.save(anchor);
    // Every request signs in afresh rather than reusing the previous request's minted session.
    ReflectionTestUtils.setField(tokenService, "sessionMintReuseWindow", Duration.ZERO);
    setAutoCreateUsers(true);
  }

  @AfterEach
  void restore() {
    setAutoCreateUsers(true);
    ReflectionTestUtils.setField(tokenService, "sessionMintReuseWindow", Duration.ofSeconds(60));
  }

  // --- auto-create users on: today's behaviour --------------------------------------------------------------

  @Test
  void openSignUpRegistersAnUnknownIdentityOnTheForwardedJwt() throws Exception {
    String email = email();

    signIn(jwtRequest(email, List.of("staff"))).andExpect(status().isOk());

    assertThat(stored(email).getType()).isEqualTo(UserType.user);
  }

  @Test
  void openSignUpRegistersAnUnknownIdentityOnTheForwardedEmailHeader() throws Exception {
    String email = email();

    signIn(get(PROFILE).servletPath(PROFILE).header("x-forwarded-email", email))
        .andExpect(status().isOk());

    assertThat(stored(email).getType()).isEqualTo(UserType.user);
  }

  // --- auto-create users off ----------------------------------------------------------------------------

  @Test
  void inviteOnlyRefusesAnUnknownIdentityOnEveryProxyPath() throws Exception {
    setAutoCreateUsers(false);
    String viaJwt = email();
    String viaHeader = email();

    signIn(jwtRequest(viaJwt, List.of("staff"))).andExpect(status().isForbidden());
    signIn(get(PROFILE).servletPath(PROFILE).header("x-forwarded-email", viaHeader))
        .andExpect(status().isForbidden());

    assertThat(userRepository.findByEmail(viaJwt)).isNull();
    assertThat(userRepository.findByEmail(viaHeader)).isNull();
  }

  @Test
  void inviteOnlyAdmitsAPersonAnAdminAddedAndActivatesThem() throws Exception {
    String email = email();
    // What adding a member to a workspace by email does: an inactive user.
    userService.getAndRegisterUser(
        email, Optional.empty(), Optional.of(UserType.user), Optional.of(UserStatus.inactive), true);
    setAutoCreateUsers(false);

    signIn(jwtRequest(email, List.of("staff"))).andExpect(status().isOk());

    UserEntity invited = stored(email);
    assertThat(invited.getStatus()).isEqualTo(UserStatus.active);
    assertThat(invited.getType()).isEqualTo(UserType.user);
  }

  @Test
  void inviteOnlyAdmitsAndCreatesAnAdminTheClaimNames() throws Exception {
    setAutoCreateUsers(false);
    String email = email();

    signIn(jwtRequest(email, List.of("flow-admins"))).andExpect(status().isOk());

    assertThat(stored(email).getType()).isEqualTo(UserType.admin);
  }

  @Test
  void inviteOnlyAdmitsAClaimAdminOnTheVerifiedIdTokenPath() {
    setAutoCreateUsers(false);
    String email = email();

    // What the sign-in exchange passes for a signature-verified id_token (webapp-run provider, IDPZero).
    tokenService.createSessionTokenWithRaw(email, "A", "B", true, true, Boolean.TRUE);

    assertThat(stored(email).getType()).isEqualTo(UserType.admin);
  }

  @Test
  void inviteOnlyRefusesAnUnknownIdentityOnTheVerifiedIdTokenPathWithoutTheClaim() {
    setAutoCreateUsers(false);
    String email = email();

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> tokenService.createSessionTokenWithRaw(email, "A", "B", true, true, Boolean.FALSE))
        .isInstanceOf(org.springframework.web.client.HttpClientErrorException.class);
    assertThat(userRepository.findByEmail(email)).isNull();
  }

  // --- the claim decides admin on every sign-in ---------------------------------------------------

  @Test
  void aClaimPromotesAnExistingUserAndItsRemovalDemotesThemEndingTheirSessions() throws Exception {
    String email = email();
    signIn(jwtRequest(email, List.of("staff"))).andExpect(status().isOk());
    assertThat(stored(email).getType()).isEqualTo(UserType.user);

    signIn(jwtRequest(email, List.of("flow-admins"))).andExpect(status().isOk());
    UserEntity admin = stored(email);
    assertThat(admin.getType()).isEqualTo(UserType.admin);
    String rawSession = tokenService.createSessionTokenForUser(admin).rawToken();
    assertThat(tokenService.validate(rawSession)).isTrue();

    signIn(jwtRequest(email, List.of("staff"))).andExpect(status().isOk());

    assertThat(stored(email).getType()).isEqualTo(UserType.user);
    assertThat(tokenService.validate(rawSession)).isFalse();
  }

  @Test
  void aPathThatCarriesNoClaimNeverChangesAnAdminsType() throws Exception {
    String email = email();
    signIn(jwtRequest(email, List.of("flow-admins"))).andExpect(status().isOk());

    signIn(get(PROFILE).servletPath(PROFILE).header("x-forwarded-email", email))
        .andExpect(status().isOk());

    assertThat(stored(email).getType()).isEqualTo(UserType.admin);
  }

  private org.springframework.test.web.servlet.ResultActions signIn(
      MockHttpServletRequestBuilder request) throws Exception {
    SecurityContextHolder.clearContext();
    return mockMvc.perform(request);
  }

  private static MockHttpServletRequestBuilder jwtRequest(String email, List<String> groups) {
    String jwt =
        new PlainJWT(
                new JWTClaimsSet.Builder().claim("email", email).claim("groups", groups).build())
            .serialize();
    return get(PROFILE).servletPath(PROFILE).header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt);
  }

  private UserEntity stored(String email) {
    UserEntity user = userRepository.findByEmail(email);
    assertThat(user).as("user %s", email).isNotNull();
    return user;
  }

  private static String email() {
    return "signin-" + UUID.randomUUID() + "@example.test";
  }

  private void setAutoCreateUsers(boolean on) {
    SettingEntity features = settingsService.getSettingByKey("features");
    features.getConfig().stream()
        .filter(config -> "autoCreateUsers".equals(config.getKey()))
        .forEach(config -> config.setValue(Boolean.toString(on)));
    settingsService.updateSetting(features);
  }
}
