package io.boomerang.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.error.RestErrorResponse;
import io.boomerang.common.model.Task;
import io.boomerang.core.security.FlowAuthenticationException;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import jakarta.validation.Valid;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.method.ControllerAdviceBean;

/**
 * Registration guard for {@link RestExceptionHandler}, which moved from {@code io.boomerang.api} to
 * {@code io.boomerang.core}. Nothing imports the class - it reaches Spring MVC purely by component
 * scan from {@code @SpringBootApplication} on {@code io.boomerang.Application} - so a bad package
 * or a lost annotation would not break the build. It would silently unregister the advice, and
 * every API error would start coming back as the framework's default error page instead of {@link
 * RestErrorResponse}. That failure is invisible to the compiler, which is why it is pinned here.
 *
 * <p>The first test asserts discovery through {@link ControllerAdviceBean#findAnnotatedBeans} - the
 * same mechanism {@code ExceptionHandlerExceptionResolver} itself uses to collect advice from the
 * context. The remaining tests drive the context's real handler bean (with its {@code MessageSource}
 * and {@code flow.error.include-cause} genuinely injected) over MockMvc and assert the serialised
 * body is the documented {@code RestErrorResponse} shape.
 */
class RestExceptionHandlerRegistrationTest extends AbstractEngineIntegrationTest {

  @Autowired private WebApplicationContext context;

  @Test
  void handlerIsRegisteredAsControllerAdviceFromItsCorePackage() {
    assertThat(context.getBeansOfType(RestExceptionHandler.class))
        .as("RestExceptionHandler must still be component-scanned after the api -> core move")
        .hasSize(1);

    assertThat(ControllerAdviceBean.findAnnotatedBeans(context))
        .as("Spring MVC's own advice discovery must see the handler")
        .extracting(ControllerAdviceBean::getBeanType)
        .contains(RestExceptionHandler.class);

    assertThat(RestExceptionHandler.class.getPackageName()).isEqualTo("io.boomerang.core");

    // Negative control, so the two assertions above cannot pass vacuously: a plain POJO that is
    // not a component resolves to no beans in this very same context.
    assertThat(context.getBeansOfType(RestErrorResponse.class))
        .as("a non-component type must resolve to no beans")
        .isEmpty();
  }

  @Test
  void boomerangExceptionRendersTheRestErrorResponseShape() throws Exception {
    // Message is left null on the exception, so the handler must resolve it through the injected
    // MessageSource against messages.properties - the CLAUDE.md documented example verbatim.
    mockMvc()
        .perform(get("/test-boom"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(1001))
        .andExpect(jsonPath("$.reason").value("QUERY_INVALID_FILTERS"))
        .andExpect(jsonPath("$.message").value("Invalid query filters(status) have been provided."))
        .andExpect(jsonPath("$.status").value("400 BAD_REQUEST"))
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  void authenticationExceptionRendersTheRestErrorResponseShape() throws Exception {
    mockMvc()
        .perform(get("/test-auth"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
        .andExpect(jsonPath("$.code").value(1004))
        .andExpect(jsonPath("$.reason").value("AUTH_TOKEN_INVALID"))
        .andExpect(jsonPath("$.message").value("Token is not valid."))
        .andExpect(jsonPath("$.status").value("401 UNAUTHORIZED"));
  }

  /*
   * A @Valid @RequestBody failure on the real Task model must resolve to the ResourceName
   * constraint's TASK_INVALID_NAME, whether the name is present-but-invalid or absent entirely -
   * before this, an absent name NPE'd to 500 rather than 400 (TaskService.java:276).
   */
  @Test
  void invalidTaskNameRendersTaskInvalidName() throws Exception {
    mockMvc()
        .perform(
            post("/test-valid-task")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"bad name!\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(1403))
        .andExpect(jsonPath("$.reason").value("TASK_INVALID_NAME"));
  }

  @Test
  void missingTaskNameRendersTaskInvalidNameNotServerError() throws Exception {
    mockMvc()
        .perform(post("/test-valid-task").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(1403))
        .andExpect(jsonPath("$.reason").value("TASK_INVALID_NAME"));
  }

  @Test
  void reservedParamNameRendersParamInvalidName() throws Exception {
    mockMvc()
        .perform(
            post("/test-valid-task")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\": \"ok-name\", \"spec\": {\"params\": [{\"name\": \"NAMES\"}]}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(1210))
        .andExpect(jsonPath("$.reason").value("PARAM_INVALID_NAME"));
  }

  private MockMvc mockMvc() {
    return MockMvcBuilders.standaloneSetup(new ThrowingController())
        .setControllerAdvice(context.getBean(RestExceptionHandler.class))
        .build();
  }

  /**
   * A throwaway controller so the two handler methods are exercised deterministically, rather than
   * by coaxing a real endpoint into failing.
   */
  @RestController
  static class ThrowingController {

    @GetMapping("/test-boom")
    String boom() {
      throw new BoomerangException(BoomerangError.QUERY_INVALID_FILTERS, "status");
    }

    @GetMapping("/test-auth")
    String auth() {
      throw new FlowAuthenticationException(BoomerangError.AUTH_TOKEN_INVALID, "Token is not valid.");
    }

    // Mirrors TaskControllerV2#create's @Valid @RequestBody Task wiring, driving the real Task
    // model's @ResourceName/@ParamName constraints without depending on TaskService.
    @PostMapping("/test-valid-task")
    Task validTask(@Valid @RequestBody Task task) {
      return task;
    }
  }
}
