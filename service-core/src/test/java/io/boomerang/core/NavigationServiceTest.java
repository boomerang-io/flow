package io.boomerang.core;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.core.model.Features;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

/**
 * The external-navigation-service branch proxies on behalf of a real user's email (the JWT
 * subject) - unlike the Flow-internal navigation default, there is no sensible unscoped answer
 * with no current user (e.g. {@code flow.security.enabled=false} - {@code
 * UserService.getCurrentUser()} resolves to {@code null}), so it must fail clearly instead of
 * NPE-ing on {@code userService.getCurrentUser().getEmail()}.
 */
@ExtendWith(MockitoExtension.class)
class NavigationServiceTest {

  @Mock private RestTemplate restTemplate;
  @Mock private ExternalTokenService apiTokenService;
  @Mock private FeatureService featureService;
  @Mock private UserService userService;
  @Mock private MongoTemplate mongoTemplate;

  private NavigationService navigationService;

  @BeforeEach
  void setUp() {
    navigationService =
        new NavigationService(restTemplate, apiTokenService, featureService, userService);
    ReflectionTestUtils.setField(
        navigationService, "flowExternalUrlNavigation", "https://external-nav.example.com");
    ReflectionTestUtils.setField(navigationService, "flowAppsUrl", "https://flow.example.com");
    Features features = new Features();
    features.setFeatures(Map.of("activity", true, "insights", true));
    when(featureService.get()).thenReturn(features);
  }

  @Test
  void externalNavigationWithNoCurrentUserFailsClearlyNotNpe() {
    when(userService.getCurrentUser()).thenReturn(null);

    assertThatThrownBy(() -> navigationService.getNavigation(false, Optional.empty()))
        .asInstanceOf(InstanceOfAssertFactories.type(BoomerangException.class))
        .extracting(BoomerangException::getReason)
        .isEqualTo(BoomerangError.AUTH_REQUIRED.getReason());
  }
}
