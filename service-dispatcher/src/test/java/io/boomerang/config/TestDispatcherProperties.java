package io.boomerang.config;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.test.context.support.TestPropertySourceUtils;

/**
 * Registered through {@code META-INF/spring.factories} on the test classpath, so every
 * {@code @SpringBootTest} in this module gets it without naming it.
 *
 * <p>Switches the two scheduled reconcilers off. Both fire as soon as a context starts and list
 * resources through whatever client is wired at that moment - the placeholder from {@link
 * TestKubeClientConfig}, not the per-class mock server, which tests only swap in from their
 * {@code @BeforeEach}. Each tick then waited out a connection failure, and because the Spring test
 * framework keeps every context alive for the rest of the JVM, the ticks from earlier classes
 * stalled the start of later ones by around eighteen seconds apiece. The reconcilers' own tests
 * construct them directly and call {@code reconcile()}, so they never need the scheduled bean.
 *
 * <p>Sits just below any inlined {@code @TestPropertySource} so a test can still turn a
 * reconciler back on explicitly.
 */
public class TestDispatcherProperties implements EnvironmentPostProcessor {

  static final String NAME = "testDispatcherProperties";

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    MapPropertySource source =
        new MapPropertySource(
            NAME,
            Map.of(
                "flow.dispatcher.workspace.reconcile.enabled", "false",
                "flow.dispatcher.task.reconcile.enabled", "false"));
    MutablePropertySources sources = environment.getPropertySources();
    if (sources.contains(TestPropertySourceUtils.INLINED_PROPERTIES_PROPERTY_SOURCE_NAME)) {
      sources.addAfter(TestPropertySourceUtils.INLINED_PROPERTIES_PROPERTY_SOURCE_NAME, source);
    } else {
      sources.addFirst(source);
    }
  }
}
