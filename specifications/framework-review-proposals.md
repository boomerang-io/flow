# Framework Review — Before / After Proposals

📝 PROPOSED — before/after for maintainer decision; nothing applied; companion to `framework-review-wave.md`.

Versions verified against `pom.xml` / `service-core/pom.xml` / `lib-common/pom.xml` and the resolved jars in
`~/.m2`: Spring Boot 4.1.0 (`spring-boot-starter-parent`), Spring Framework 7.0.8, Spring Data MongoDB 5.1.0,
Java 25 (`service-core/pom.xml:18`), Hibernate Validator 9.1.0.Final and `jakarta.validation-api` 3.1.1 managed
by the Boot BOM. Sections: A7, A8, A11, then A9/A10/A16.

---

## A7 — Bean Validation for resource / parameter names

**BLUF.** Apply one shared constant now (it fixes a real bug); wire `@Valid` only if the OpenAPI schema benefit
is wanted. Name validation is inline in 4 services with 2 different regexes; `WorkflowTemplateService.java:45`
double-escapes its regex and therefore admits a literal backslash in a template name (verified with jshell:
`"a\\b".matches("^([0-9a-zA-Z\\\\-]+)$")` is `true`; the `TaskService` regex rejects it). Bean Validation can
carry the regex checks (`TASK_INVALID_NAME`, `PARAM_INVALID_NAME`) to the controller edge and into the
springdoc schema, but the service checks MUST stay because the YAML/Tekton endpoints post a `TektonTask` body
and only reach the `Task` name after conversion (`TaskService.java:511-514`). Everything that needs a
database lookup or a cross-element comparison stays in services.

### Before (verbatim)

`service-core/src/main/java/io/boomerang/workflow/TaskService.java:90`
```java
  private static final String NAME_REGEX = "^([0-9a-zA-Z\\-]+)$";
```
`service-core/src/main/java/io/boomerang/workflow/TaskService.java:275-278` (same shape at `:313`, `:401`, `:428`, `:535`, `:629`, `:667`)
```java
    // Check name matches the requirements
    if (request.getName().isBlank() || !request.getName().matches(NAME_REGEX)) {
      throw new BoomerangException(BoomerangError.TASK_INVALID_NAME, request.getName());
    }
```
`service-core/src/main/java/io/boomerang/workflow/WorkflowTemplateService.java:45` and `:156-160` (same at `:233-237`)
```java
  private static final String NAME_REGEX = "^([0-9a-zA-Z\\\\-]+)$";
```
```java
    // Name Check
    if (!request.getName().matches(NAME_REGEX)) {
      // TODO change the error
      throw new BoomerangException(BoomerangError.TASK_INVALID_NAME, request.getName());
    }
```
`lib-common/src/main/java/io/boomerang/common/util/ParameterUtil.java:83-89`
```java
  public static final String PARAM_NAME_REGEX = "^[a-zA-Z_][a-zA-Z0-9_-]*$";

  public static boolean isValidParamName(String name) {
    // "names" (any casing/separator variant) is reserved: it folds to PARAM_NAMES, the env var
    // that carries the param-name manifest itself, and would clobber it.
    return name != null && name.matches(PARAM_NAME_REGEX) && !"NAMES".equals(envFold(name));
  }
```
`service-core/src/main/java/io/boomerang/workflow/WorkflowService.java:1576-1595` (the param-name half of `validateDeclaredParams`)
```java
    if (wfTask.getParams() != null) {
      wfTask.getParams().stream()
          .map(RunParam::getName)
          .filter(name -> !ParameterUtil.isValidParamName(name))
          .findFirst()
          .ifPresent(
              name -> {
                throw new BoomerangException(BoomerangError.PARAM_INVALID_NAME, name);
              });
      // Case/separator-variant duplicates collide as PARAM_<NAME> env vars and, under
      // case-insensitive matching, as references - rejected here rather than at dispatch.
      List<List<String>> collisions =
          ParameterUtil.paramNameCollisions(
              wfTask.getParams().stream().map(RunParam::getName).collect(Collectors.toList()));
      if (!collisions.isEmpty()) {
        throw new BoomerangException(
            BoomerangError.PARAM_NAME_COLLISION,
            collisions.toString(),
            ParameterUtil.envFold(collisions.get(0).get(0)));
      }
    }
```
`service-core/src/main/java/io/boomerang/workspace/WorkspaceService.java:546-553`
```java
      List<String> names = request.stream().map(AbstractParam::getName).toList();
      names.stream()
          .filter(name -> !ParameterUtil.isValidParamName(name))
          .findFirst()
          .ifPresent(
              name -> {
                throw new BoomerangException(BoomerangError.PARAM_INVALID_NAME, name);
              });
```
`TaskService.java:353-376` (`validateDeclaredParamNames`) repeats the `WorkflowService` block for Task templates.
`service-core/src/main/resources/messages.properties:41` says "lower case alphanumeric" but the regex accepts upper case
(`"MyTask".matches(NAME_REGEX)` is `true`).

### After

Dependencies. `hibernate-validator` 9.1.0.Final is ALREADY on `service-core`'s runtime classpath transitively
(`springdoc-openapi-starter-common:3.0.3` → `spring-boot-validation:4.1.0` → `hibernate-validator`, compile
scope; verified in the `~/.m2` poms). Relying on a documentation tool for validation is fragile, so:

| Module | Add to pom | Version |
| --- | --- | --- |
| `service-core/pom.xml` | `org.springframework.boot:spring-boot-starter-validation` | managed (4.1.0) |
| `lib-common/pom.xml` | `jakarta.validation:jakarta.validation-api` | managed (3.1.1) — the models carrying the annotations live here |

New: `lib-common/src/main/java/io/boomerang/common/validation/ResourceName.java` (one constant, one composed constraint)
```java
package io.boomerang.common.validation;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import io.boomerang.common.error.BoomerangError;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/** Slug rule for Task / WorkflowTemplate names. The single source for the regex that TaskService:90 and
 *  WorkflowTemplateService:45 each declared (the latter with a double-escape that admitted '\\'). */
@Documented
@Constraint(validatedBy = {})
@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
@NotBlank
@Pattern(regexp = ResourceName.REGEX)
@ReportAsSingleViolation
public @interface ResourceName {
  String REGEX = "^[0-9a-zA-Z-]+$";

  /** Which platform error the violation maps to - read by RestExceptionHandler. */
  BoomerangError error() default BoomerangError.TASK_INVALID_NAME;

  String message() default "invalid resource name";
  Class<?>[] groups() default {};
  Class<? extends Payload>[] payload() default {};
}
```
New: `lib-common/src/main/java/io/boomerang/common/validation/ParamName.java` + `ParamNameValidator.java`
(a `ConstraintValidator` because the reserved-`NAMES` rule is not a regex)
```java
@Documented
@Constraint(validatedBy = ParamNameValidator.class)
@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
public @interface ParamName {
  String message() default "invalid parameter name";
  Class<?>[] groups() default {};
  Class<? extends Payload>[] payload() default {};
}

public final class ParamNameValidator implements ConstraintValidator<ParamName, String> {
  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    return value == null || ParameterUtil.isValidParamName(value); // null is @NotNull's job
  }
}
```
Models (`lib-common/.../model/Task.java:18`, `WorkflowTemplate.java:48`, `AbstractParam.java:16`, `TaskSpec.params`)
```java
  @ResourceName private String name;                 // Task, WorkflowTemplate
  @ParamName private String name;                    // AbstractParam (RunParam/ParamSpec are standalone classes with their own name field — not covered)
  @Valid private TaskSpec spec = new TaskSpec();     // Task - cascade into spec.params
```
Controllers — 6 `@RequestBody` parameters gain `@Valid`:
`TaskControllerV2.java:148,187`, `WorkspaceTaskControllerV2.java:180,234`, `WorkflowTemplateControllerV2.java:127,143`
```java
  public Task create(@Valid @RequestBody Task task) {
```
`service-core/src/main/java/io/boomerang/core/RestExceptionHandler.java` — override the hook that
`ResponseEntityExceptionHandler` already exposes (signature verified by `javap` on `spring-webmvc-7.0.8.jar`)
and route through the existing `handleBoomerangException` so the body stays the CLAUDE.md `RestErrorResponse` shape:
```java
  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    FieldError first = ex.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
    if (first == null) {
      return handleBoomerangException(new BoomerangException(BoomerangError.QUERY_INVALID_FILTERS, "body"));
    }
    return handleBoomerangException(new BoomerangException(errorFor(first), first.getRejectedValue()));
  }

  private static BoomerangError errorFor(FieldError fieldError) {
    try {
      Annotation constraint =
          fieldError.unwrap(ConstraintViolation.class).getConstraintDescriptor().getAnnotation();
      if (constraint instanceof ResourceName resourceName) {
        return resourceName.error();
      }
      if (constraint instanceof ParamName) {
        return BoomerangError.PARAM_INVALID_NAME;
      }
    } catch (IllegalArgumentException notAConstraintViolation) {
      // fall through - a binding error rather than a constraint violation
    }
    return BoomerangError.QUERY_INVALID_FILTERS;
  }
```
The fallback reuses `QUERY_INVALID_FILTERS` (1001) because no generic "invalid request body" code exists;
`1006` is the next free 10xx code (`BoomerangError.java:27-32`) if the maintainer prefers a new `REQUEST_INVALID_BODY`.

Services keep their checks but read the constant: `TaskService.java:90` and `WorkflowTemplateService.java:45` become
`private static final String NAME_REGEX = ResourceName.REGEX;` (or delete the field and call `.matches(ResourceName.REGEX)`).

### Blast radius

| File | Change | Sites |
| --- | --- | --- |
| `lib-common/pom.xml`, `service-core/pom.xml` | add managed deps | 2 |
| `lib-common/.../validation/{ResourceName,ParamName,ParamNameValidator}.java` | new | 3 files |
| `lib-common/.../model/Task.java:18`, `WorkflowTemplate.java:48`, `AbstractParam.java:16`, `TaskSpec` | annotate | 4 |
| `TaskControllerV2.java:148,187`; `WorkspaceTaskControllerV2.java:180,234`; `WorkflowTemplateControllerV2.java:127,143` | `@Valid` | 6 of 46 `@RequestBody` params |
| `TaskService.java:90` (+7 `matches(NAME_REGEX)` uses); `WorkflowTemplateService.java:45` (+2 uses) | constant swap | 2 files, 9 uses |
| `core/RestExceptionHandler.java` | 1 override + 1 helper | 1 |
| `messages.properties:41` | wording ("lower case" is false) | 1 |
| Tests: `RestExceptionHandlerRegistrationTest` (+1 MockMvc case), `WorkflowTaskParamValidationTest`, `TaskWorkspaceAuthorizationTest` | the latter two call services directly — unchanged | 3 |

### Behavioural differences and risks

| Aspect | Before | After |
| --- | --- | --- |
| Error body | `{code:1403, reason:"TASK_INVALID_NAME", message:"...Supplied: {0}", status:"400 BAD_REQUEST"}` | identical — same `BoomerangError`, same `messages.properties` key, `{0}` = rejected value |
| `name` absent in JSON | `request.getName().isBlank()` NPE → 500 (`TaskService.java:276`) | 400 `TASK_INVALID_NAME` via `@NotBlank` |
| Backslash in a WorkflowTemplate name | accepted (`:45` regex bug) | rejected |
| Ordering vs authorisation | `SecurityInterceptor` (`HandlerInterceptor`) runs before argument resolution, so `@AuthCriteria` still precedes validation; only the in-service `relationshipService.check` (`TaskService.java:270`) now runs after the name check | 400 instead of 403 for an invalid name from a caller who passes the interceptor but fails the service check — edge case |
| YAML/Tekton endpoints | service regex | unchanged — the body is `TektonTask`; the `Task` name is only known after `TektonConverter` (`TaskService.java:511-514`) |
| Property names | none involved | none change |

MUST stay in services (cannot be Bean Validation): uniqueness via `relationshipService.filter` (`TaskService.java:280-289`),
`taskRepository.existsByName` (`:636`), `wfTemplateRepository.findByNameAndLatestVersion` (`WorkflowTemplateService.java:163`);
workspace slug uniqueness + reserved names (`WorkspaceService.java:133-138`, kebab-cased first); `PARAM_NAME_COLLISION`
(cross-element, `ParameterUtil.paramNameCollisions`); undeclared-param-vs-template (`WorkflowService.java:1597+`, DB lookup);
anything gated by `flow.uniquenames.enabled` (`TaskService.java:94`).

### Recommendation
SHOULD apply the shared `ResourceName.REGEX` constant + `messages.properties:41` fix now (2 files, fixes a bug).
MAY apply the `@Valid` edge wiring afterwards; its concrete gain is the springdoc schema `pattern` and the 500→400 on a
missing name, at the cost of a second validation path to keep consistent. Do NOT remove the service checks.

---

## A8 — `@ConfigurationProperties`

**BLUF.** 108 `@Value("${…}")` sites repo-wide (40 in `service-core/src/main`, 68 in `service-dispatcher`, 0 in
`lib-common`/`service-loader`), zero `@ConfigurationProperties`. Three clusters are worth converting: (a)
`EncryptionConfig` is a `@Configuration` used purely as a value holder; (b) the three watcher/outbox sweep
intervals are `@Scheduled` placeholders whose defaults live only in annotation strings; (c) the security gate
CANNOT move — `@Conditional` evaluates at bean-definition time, before any `@ConfigurationProperties` bean is bound.
Property names MUST NOT change; every record below binds the existing keys.

### Inventory (all `@Value("${` sites, repo-wide, by top-level key)

| Prefix | Sites | Module | Prefix | Sites | Module |
| --- | --- | --- | --- | --- | --- |
| `kube.task` | 14 | dispatcher | `flow.watcher` | 2 (+1 `@Scheduled`) | core |
| `flow.engine` | 10 | core 2 / dispatcher 8 | `flow.events` | 2 (+1 `@Scheduled`) | core |
| `dispatcher.tasks` | 10 | dispatcher | `mongo.encrypt` | 2 | core |
| `kube.resource` | 8 | dispatcher | `flow.version`, `flow.product` | 2 each | core/dispatcher |
| `kube.image` | 6 | dispatcher | `flow.schedule` | 1 (+1 `@Scheduled`) | core |
| `flow.externalUrl` | 5 | core | `proxy.ignore`, `proxy.enable` | 1 each | dispatcher |
| `kube.timeout` | 4 | dispatcher | `flow.{workflowrun,uniquenames,token,signOutUrl,queue,otc,mongo,instance,error,baseUrl,authorization,agent}` | 1 each (12) | core |
| `proxy.host`, `proxy.port` | 3 each | core 1 / dispatcher 2 | `core.platform`, `api.token` | 1 each | core |
| `kube.workspace`, `flow.workflow`, `flow.dispatcher`, `flow.apps`, `dispatcher.logging`, `core.feature` | 3 each | mixed | | | |

Counts from `grep -rn '@Value("\${' --include=*.java .` (108 lines) split by the first two dotted segments.
Three more `${…}` placeholders sit inside `@Scheduled(fixedDelayString=…)` (`WorkflowWatcher.java:119`,
`OutboxDispatcher.java:61`, `ScheduleWatcher.java:71`) and are not `@Value`.

### (a) `EncryptionConfig` — value holder masquerading as configuration

Before — `service-core/src/main/java/io/boomerang/core/model/EncryptionConfig.java:1-18`
```java
package io.boomerang.core.model;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

@Data
@Configuration
@PropertySource("classpath:application.properties")
public class EncryptionConfig {

  @Value("${mongo.encrypt.secret:secret}")
  private String secretKey;

  @Value("${mongo.encrypt.salt:salt}")
  private String salt;
}
```
Consumer — `service-core/src/main/java/io/boomerang/core/SettingsService.java:34-38,156,168`
```java
  private final EncryptionConfig encryptConfig;
...
            + AESAlgorithm.encrypt(value, encryptConfig.getSecretKey(), encryptConfig.getSalt())
```
After — `service-core/src/main/java/io/boomerang/core/config/EncryptionProperties.java` (new; delete `EncryptionConfig`)
```java
package io.boomerang.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Binds mongo.encrypt.* (application.properties:108-109). Keys are unchanged. */
@ConfigurationProperties(prefix = "mongo.encrypt")
public record EncryptionProperties(
    @DefaultValue("secret") String secret,
    @DefaultValue("salt") String salt) {}
```
`SettingsService.java:156,168` → `encryption.secret()`, `encryption.salt()`. Registration: one annotation on
`service-core/src/main/java/io/boomerang/Application.java` — `@ConfigurationPropertiesScan` next to
`@SpringBootApplication` — covers every record in this section. The `@PropertySource("classpath:application.properties")`
is redundant (Boot loads that file itself) and removing it is behaviour-neutral. `application.properties:108-109`
ships `mongo.encrypt.secret=` (empty): both `@Value(":secret")` and `@DefaultValue("secret")` apply only when
the key is absent, so the bound value stays `""` — identical. A `@Validated @NotBlank` on `secret` would be the
right long-term guard but would fail boot on today's shipped properties, so it is NOT proposed here.

### (b) Watcher / outbox / schedule intervals

Before — `service-core/src/main/java/io/boomerang/engine/WorkflowWatcher.java:81-86` and `:116-119`
```java
  @Value("${flow.watcher.enabled:true}")
  private boolean enabled;

  // Hard pruning of tombstoned Workflows ships off - the retention policy is decided separately.
  @Value("${flow.watcher.retention.enabled:false}")
  private boolean retentionEnabled;
```
```java
  @Scheduled(
      initialDelayString =
          "#{T(java.util.concurrent.ThreadLocalRandom).current().nextLong(30000)}",
      fixedDelayString = "${flow.watcher.interval-ms:30000}")
  public void sweep() {
```
`service-core/src/main/java/io/boomerang/event/OutboxDispatcher.java:59-61`
```java
  @Scheduled(
      initialDelayString = "#{T(java.util.concurrent.ThreadLocalRandom).current().nextLong(5000)}",
      fixedDelayString = "${flow.events.outbox.interval-ms:5000}")
```
`service-core/src/main/java/io/boomerang/engine/config/SchedulingConfig.java:10-13`
```java
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "flow.watcher.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {}
```
After — `service-core/src/main/java/io/boomerang/engine/FlowWatcherProperties.java` (new)
```java
package io.boomerang.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Binds flow.watcher.* - enabled, interval-ms, retention.enabled. Keys are unchanged. */
@ConfigurationProperties(prefix = "flow.watcher")
public record FlowWatcherProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("30000") long intervalMs,
    @DefaultValue Retention retention) {

  public record Retention(@DefaultValue("false") boolean enabled) {}
}
```
`WorkflowWatcher.java` — constructor-inject the record, drop both `@Value` fields and the `@Scheduled` annotation:
```java
  private final FlowWatcherProperties properties;
  ...
  public void sweep() {
    if (!properties.enabled()) {
      return;
    }
```
`SchedulingConfig.java` — registers the sweep from the bound record (API verified: `FixedDelayTask(Runnable, Duration, Duration)`
and `ScheduledTaskRegistrar.addFixedDelayTask(IntervalTask)` exist in `spring-context-7.0.8.jar`):
```java
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "flow.watcher.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig implements SchedulingConfigurer {

  private final FlowWatcherProperties properties;
  private final WorkflowWatcher watcher;

  SchedulingConfig(FlowWatcherProperties properties, WorkflowWatcher watcher) {
    this.properties = properties;
    this.watcher = watcher;
  }

  @Override
  public void configureTasks(ScheduledTaskRegistrar registrar) {
    Duration interval = Duration.ofMillis(properties.intervalMs());
    Duration jitter = Duration.ofMillis(ThreadLocalRandom.current().nextLong(properties.intervalMs()));
    registrar.addFixedDelayTask(new FixedDelayTask(watcher::sweep, interval, jitter));
  }
}
```
The same shape applies to `flow.events` (`FlowEventsProperties(Sink sink, Outbox outbox)` — `sink.enabled`, `sink.urls`
from `EventSinkService.java:29-33`, `outbox.intervalMs` default 5000) and `flow.schedule.watcher`
(`ScheduleWatcher.java:48-49,69-71`, `ScheduleWatcherConfig.java`).

Why not keep `@Scheduled` and read the record via SpEL: a scanned `@ConfigurationProperties` record is registered under
the bean name `flow.watcher-io.boomerang.engine.FlowWatcherProperties`, so `#{@flowWatcherProperties.intervalMs()}` does not
resolve; the working form is `#{@'flow.watcher-io.boomerang.engine.FlowWatcherProperties'.intervalMs()}`, which breaks on any
rename. Records cannot be registered by `@Component`/`@Bean` (constructor binding is scan/`@EnableConfigurationProperties` only).

| Option | Fits when | Cost/risk | Recommend |
| --- | --- | --- | --- |
| Keep `@Scheduled("${…:30000}")`, move only `enabled`/`retention` into the record | minimum churn | the interval default is declared twice (record + annotation string) | no |
| `@Scheduled` + quoted SpEL bean name | one class touched | fragile string coupling to the FQCN | no |
| `SchedulingConfigurer` (shown) | typed, testable, one place per subsystem | `@Scheduled` leaves the method; 3 configs + 3 watchers touched | yes — at the E5 moment `e4-review-findings.md:104` already names |

### (c) `FlowSecurityProperties` + `SecurityEnabledCondition` / `SecurityDisabledCondition`

Before — `service-core/src/main/java/io/boomerang/core/security/FlowSecurityProperties.java:16-29`
```java
public final class FlowSecurityProperties {

  static final String UNIFIED_PROPERTY = "flow.security.enabled";

  private FlowSecurityProperties() {}

  /** Whether security (both the authentication filter chain and the authorization interceptor) should be active. */
  public static boolean isSecurityEnabled(Environment environment) {
    String unified = environment.getProperty(UNIFIED_PROPERTY);
    if (unified != null) {
      return Boolean.parseBoolean(unified);
    }
    return FlowMode.resolve(environment) == FlowMode.STANDALONE;
  }
}
```
`SecurityEnabledCondition.java:13-19`
```java
class SecurityEnabledCondition implements Condition {

  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    return FlowSecurityProperties.isSecurityEnabled(context.getEnvironment());
  }
}
```
After — no change. `@Conditional` runs while bean definitions are being registered
(`SecurityConfiguration.java:20`, `SecurityInterceptorConfiguration.java:12`, `SecurityDisabledConfiguration.java:14`);
a `Condition` receives only `ConditionContext.getEnvironment()`, and no `@ConfigurationProperties` bean exists yet.
Boot's own `@ConditionalOnProperty`/`@ConditionalOnBooleanProperty` cannot express "default derived from `flow.mode`".
There are also zero runtime consumers in `src/main` — the only callers are the two `Condition`s; tests call the static
directly (`UnauthenticatedGlobalAuthenticationFilterTest.java:143-150`). `FlowQuotaProperties.java` (`workspace/`) is the
same static pattern with one runtime consumer (`WorkflowService.java:203`) and the same verdict. MAY rename both to
`*Resolver` so the `*Properties` suffix is not mistaken for a bound bean — pure churn, not proposed.

### Blast radius

| File | Change |
| --- | --- |
| `io/boomerang/Application.java` | `@ConfigurationPropertiesScan` |
| `core/model/EncryptionConfig.java` → `core/config/EncryptionProperties.java` | delete + new record |
| `core/SettingsService.java:34-38,156,168` | 4 lines |
| `engine/FlowWatcherProperties.java`, `event/FlowEventsProperties.java`, `schedule/FlowScheduleProperties.java` | 3 new records |
| `engine/WorkflowWatcher.java:81-86,116-119`; `event/OutboxDispatcher.java:59-61`; `event/EventSinkService.java:29-33`; `schedule/ScheduleWatcher.java:48-49,69-71` | remove `@Value`/`@Scheduled`, inject record |
| `engine/config/SchedulingConfig.java`; `schedule/ScheduleWatcherConfig.java` (+ outbox registration) | `SchedulingConfigurer` |
| `core/security/*` | none |
| Tests: `AbstractEngineIntegrationTest.java:65` (`flow.watcher.enabled=false` via `DynamicPropertyRegistry`, 43 subclasses) | unchanged — binding reads the same `Environment` |

### Behavioural differences and risks
Property keys: none change (`mongo.encrypt.secret`, `flow.watcher.interval-ms` → `intervalMs` by relaxed binding).
Defaults: identical values, now in one place. `@Validated` on the records is available but MUST NOT be added for
`mongo.encrypt` until the shipped empty defaults are resolved. The `SchedulingConfigurer` route moves the jitter
computation from SpEL to Java (same `nextLong(interval)` bound). `@ConditionalOnProperty` on `SchedulingConfig`
still reads `flow.watcher.enabled` from the `Environment` — same value the record binds. Biggest risk: an
integration test that relies on `@Scheduled` being present on `sweep()` (none found; tests invoke `sweep()` directly).

### Recommendation
SHOULD do (a) now (1 record, 1 consumer, 1 annotation on `Application`). SHOULD do (b) with the E5
`ScheduleWatcher` config work. MUST NOT attempt (c). The other 34 `@Value` sites in `service-core` are single-use
strings (`flow.externalUrl.*` ×5, `flow.apps.flow.url` ×3 are the only repeats) and MAY migrate opportunistically.

---

## A11 — shared `Criteria` builder for list / count / insight queries

**BLUF.** 13 hand-built `List<Criteria>` … `andOperator` blocks across 10 files (the review said 12/9;
`ActionService.buildCriteriaList` is a 13th with two divergences) share three copy-pasted fragments: a
`creationDate` `[from, to)` range (5 identical copies + 2 variants), a URL-decoded `labels` filter (9 identical
copies), and an enum-validated `status`/`phase`/`type` `$in`. Consolidating into one ~60-line builder surfaces
three silent divergences, one of which is a live bug (`UserService.java:386` filters on the `Optional` object,
not its contents). Querydsl is NOT proposed (see the last paragraph).

### Divergence table (read every copy)

| Site | `creationDate` | labels | enum-validated `$in` | other `$in` / extra | Divergence |
| --- | --- | --- | --- | --- | --- |
| `WorkflowRunService.query :492-566` | `gte(from)`, `lt(to)` | yes | `status`(RunStatus), `phase`(RunPhase) | `id`, `workflowRef`, `trigger` | reference copy |
| `WorkflowRunService.insights :597-640` | same | yes | — | `workflowRef` | — |
| `WorkflowRunService.count :686-727` | same | yes | — | `workflowRef` | — |
| `WorkflowService.query :1318-1357` | none | yes | `status`(WorkflowStatus) | `id` | no date range although sort is on `creationDate` (`:1314`) |
| `WorkflowService.count :1397-1438` | same as ref | yes | — | `id` in `queryWorkflows` | — |
| `TaskService.query :776-822` | none | yes | `status`(TaskStatus) | `name`; `_id` via `new ObjectId(…)` | uses `_id`+ObjectId where every other site uses `id`+String (both work via QueryMapper; cosmetic) |
| `WorkspaceService.query :398-446` | none | yes | `status`(WorkspaceStatus) | `name` in teamRefs; anchored regex search on `name`/`displayName` | — |
| `TokenService.query :462-487` | same as ref | none | — | `type`, `principal` | sort field overridable (`:459`) |
| `UserService.query :356-394` | none | yes | `status`(UserStatus) | `Criteria.where("id").in(queryIds)` | **BUG**: `queryIds` is `Optional<List<String>>` (`:343`); `in(Object...)` wraps the `Optional` itself, so `ids=` never matches |
| `ScheduleService.query :125-149` | none | none | `status`(WorkflowScheduleStatus) | `workflowRef` (required), `type`; whole block inside `if (!refs.isEmpty())` | — |
| `WorkflowTemplateService.query :98-127` | none | yes | — | `name` | — |
| `AuditQueryService :50-59` | `gte(from).lt(to)` both required | none | — | `scope`, `data.<field>` | — |
| `ActionService.buildCriteriaList :323-357` | `gte(from)`, **`lte(to)`** (`:337`) | none | — | `workflowRef`, `type`, `status` (typed enums, no validation) | inclusive upper bound (every other site is exclusive); `new Criteria().andOperator(empty)` unguarded (`:356`) — MongoDB rejects `$and: []`; safe today only because all 3 callers (`:244,:306,:318`) always pass ≥1 filter |

Common to all 9 label copies (`grep -c 'URLDecoder.decode(l, "UTF-8")'` = 9): `decodedLabel.split("[=]+")` then
`label[1]` unchecked — `?labels=foo` throws `ArrayIndexOutOfBoundsException` → 500, not `QUERY_INVALID_FILTERS`;
the only guarded branch (`UnsupportedEncodingException`) cannot fire for `"UTF-8"`. The `.replace(".", "#")` matches
`MongoConfiguration.java:38 setMapKeyDotReplacement("#")` and is consistent everywhere.

### Before (one site, verbatim) — `service-core/src/main/java/io/boomerang/workflow/WorkflowRunService.java:686-728`
```java
    List<Criteria> criteriaList = new ArrayList<>();

    if (from.isPresent() && !to.isPresent()) {
      Criteria criteria = Criteria.where("creationDate").gte(from.get());
      criteriaList.add(criteria);
    } else if (!from.isPresent() && to.isPresent()) {
      Criteria criteria = Criteria.where("creationDate").lt(to.get());
      criteriaList.add(criteria);
    } else if (from.isPresent() && to.isPresent()) {
      Criteria criteria = Criteria.where("creationDate").gte(from.get()).lt(to.get());
      criteriaList.add(criteria);
    }

    // TODO add the ability to OR labels not just AND
    if (labels.isPresent()) {
      labels.get().stream()
          .forEach(
              l -> {
                String decodedLabel = "";
                try {
                  decodedLabel = URLDecoder.decode(l, "UTF-8");
                } catch (UnsupportedEncodingException e) {
                  throw new BoomerangException(e, BoomerangError.QUERY_INVALID_FILTERS, "labels");
                }
                LOGGER.debug(decodedLabel.toString());
                String[] label = decodedLabel.split("[=]+");
                Criteria labelsCriteria =
                    Criteria.where("labels." + label[0].replace(".", "#")).is(label[1]);
                criteriaList.add(labelsCriteria);
              });
    }

    if (queryWorkflows.isPresent()) {
      Criteria criteria = Criteria.where("workflowRef").in(queryWorkflows.get());
      criteriaList.add(criteria);
    }

    Criteria[] criteriaArray = criteriaList.toArray(new Criteria[criteriaList.size()]);
    Criteria allCriteria = new Criteria();
    if (criteriaArray.length > 0) {
      allCriteria.andOperator(criteriaArray);
    }
    Query query = new Query(allCriteria);
```

### After

New — `lib-common/src/main/java/io/boomerang/common/query/QueryCriteria.java` (`lib-common` already has
`spring-data-mongodb` and `BoomerangException`; `commons-lang3` is not on its classpath, so no `EnumUtils`)
```java
package io.boomerang.common.query;

import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/** The shared filter block behind every list/count/insight endpoint. Field names are the entity field names. */
public final class QueryCriteria {

  private final List<Criteria> criteria = new ArrayList<>();

  private QueryCriteria() {}

  public static QueryCriteria builder() {
    return new QueryCriteria();
  }

  /** Half-open range [from, to) on creationDate - either bound optional. */
  public QueryCriteria creationDateBetween(Optional<Date> from, Optional<Date> to) {
    if (from.isPresent() || to.isPresent()) {
      Criteria range = Criteria.where("creationDate");
      from.ifPresent(range::gte);
      to.ifPresent(range::lt);
      criteria.add(range);
    }
    return this;
  }

  /** labels=key%3Dvalue (ANDed). Map-key dots are stored as '#' (MongoConfiguration.setMapKeyDotReplacement). */
  public QueryCriteria labels(Optional<List<String>> labels) {
    for (String raw : labels.orElse(List.of())) {
      String[] pair = URLDecoder.decode(raw, StandardCharsets.UTF_8).split("=", 2);
      if (pair.length != 2 || pair[0].isBlank()) {
        throw new BoomerangException(BoomerangError.QUERY_INVALID_FILTERS, "labels");
      }
      criteria.add(Criteria.where("labels." + pair[0].replace(".", "#")).is(pair[1]));
    }
    return this;
  }

  /** $in on an enum-typed field; values validated case-insensitively, else QUERY_INVALID_FILTERS(field). */
  public <E extends Enum<E>> QueryCriteria enumIn(String field, Class<E> type, Optional<List<String>> values) {
    values.ifPresent(
        list -> {
          boolean valid =
              list.stream()
                  .allMatch(v -> Arrays.stream(type.getEnumConstants()).anyMatch(e -> e.name().equalsIgnoreCase(v)));
          if (!valid) {
            throw new BoomerangException(BoomerangError.QUERY_INVALID_FILTERS, field);
          }
          criteria.add(Criteria.where(field).in(list));
        });
    return this;
  }

  public QueryCriteria in(String field, Optional<? extends Collection<?>> values) {
    values.ifPresent(list -> criteria.add(Criteria.where(field).in(list)));
    return this;
  }

  public QueryCriteria add(Criteria extra) {
    criteria.add(extra);
    return this;
  }

  /** Empty builder = match-all. Never emits `$and: []`, which MongoDB rejects. */
  public Criteria build() {
    return criteria.isEmpty() ? new Criteria() : new Criteria().andOperator(criteria);
  }

  public Query toQuery() {
    return new Query(build());
  }
}
```
(`Criteria.andOperator(Collection<Criteria>)` and `in(Collection<?>)` verified by `javap` on `spring-data-mongodb-5.1.0.jar`.)

After — `WorkflowRunService.count` (the 42 lines above become 6)
```java
    Query query =
        QueryCriteria.builder()
            .creationDateBetween(from, to)
            .labels(labels)
            .in("workflowRef", queryWorkflows)
            .toQuery();
```
After — `WorkflowRunService.query :492-566` for comparison
```java
    Query query =
        QueryCriteria.builder()
            .creationDateBetween(from, to)
            .labels(queryLabels)
            .enumIn("status", RunStatus.class, queryStatus)
            .enumIn("phase", RunPhase.class, queryPhase)
            .in("id", queryWorkflowRuns)
            .in("workflowRef", queryWorkflows)
            .in("trigger", queryTriggers)
            .toQuery();
```

### Blast radius

| File | Sites | Lines removed (approx.) |
| --- | --- | --- |
| `lib-common/.../common/query/QueryCriteria.java` | new | +70 |
| `workflow/WorkflowRunService.java:492-566, 597-640, 686-727` | 3 | −120 |
| `workflow/WorkflowService.java:1318-1357, 1397-1438` | 2 | −70 |
| `workflow/TaskService.java:776-822` | 1 | −40 (keep the `ObjectId` mapping or switch to `in("id", …)`) |
| `workspace/WorkspaceService.java:398-446` | 1 | −30 (regex search stays via `.add(searchCriteria)`) |
| `core/TokenService.java:462-487` | 1 | −20 |
| `core/UserService.java:356-394` | 1 | −30 (+ fixes `:386`) |
| `schedule/ScheduleService.java:125-149` | 1 | −15 |
| `workflow/WorkflowTemplateService.java:98-127` | 1 | −25 |
| `core/audit/AuditQueryService.java:50-59` | 1 | −8 (`creationDateBetween(Optional.of(from), Optional.of(to))`) |
| `workflow/ActionService.java:323-357` | 1 | −25 — only if the maintainer accepts `lt(to)` (see risks) |
| Tests | list/count endpoints in the 43 `AbstractEngineIntegrationTest` subclasses exercise these paths; add one unit test for `QueryCriteria` (empty → match-all, bad label → 1001, bad enum → 1001) | +1 file |

### Behavioural differences and risks

| Change | Before | After | Intentional? |
| --- | --- | --- | --- |
| `?labels=foo` (no `=`) | 500 `ArrayIndexOutOfBoundsException` | 400 `QUERY_INVALID_FILTERS` "labels" | yes |
| `?labels=a%3Db%3Dc` | key `a`, value `b` (`split("[=]+")` drops `c`) | key `a`, value `b=c` | yes — document |
| `?labels=a%3D%3Db` | key `a`, value `b` | key `a`, value `=b` | edge; document |
| `UserService ids=` filter | matches nothing (bug) | matches the ids | yes — a fix, may change what an existing caller sees |
| `ActionService` `to` bound | inclusive `lte` | exclusive `lt` if migrated | MUST be an explicit decision; otherwise leave `ActionService` on `.add(Criteria.where("creationDate").lte(to))` |
| Empty filter set | `new Criteria()` (match-all) everywhere except `ActionService` (`$and: []`) | match-all everywhere | yes |
| Error body | `RestErrorResponse` code 1001 / reason `QUERY_INVALID_FILTERS` | identical | — |

Querydsl: verified that the Boot 4.1.0 BOM manages `com.querydsl:querydsl-bom:5.1.0` and that
`spring-data-mongodb-5.1.0.jar` still ships `QuerydslMongoPredicateExecutor`; NOT verified (no Querydsl jar has
ever been resolved into `~/.m2` here, so it was never compiled) that `querydsl-apt` generates Q-classes on Java 25
with Jackson 3 present. It would add an annotation processor, `@QueryEntity` on every entity and a build step to
replace a 70-line class — not proposed.

### Recommendation
SHOULD apply: the builder plus the 9 label-bearing sites and `TokenService`/`AuditQueryService` (11 sites),
fixing `UserService.java:386` in the same change. `ActionService` MUST be migrated only after the maintainer
rules on `lte` vs `lt`. Biggest risk: a client that today relies on the `a=b=c` label truncation.

---

## A9 — Spring Data auditing feasibility (single-instance MongoDB / DocumentDB)

**BLUF.** `@EnableMongoAuditing` works on (a) a single-instance free-tier MongoDB and (b) DocumentDB / Cosmos DB
for MongoDB, because auditing is entirely client-side: `AuditingEntityCallback implements BeforeConvertCallback`
(`spring-data-mongodb-5.1.0-sources.jar`, `core/mapping/event/AuditingEntityCallback.java:onBeforeConvert` →
`IsNewAwareAuditingHandler.markAudited`) stamps the Java object before `MongoTemplate` converts it — no replica
set, transaction, change stream or `$currentDate` is involved. The finding that matters for THIS codebase is
narrower: the callback fires only from `MongoTemplate.doSave` (`:1600,1630`), `doInsertBatch` (`:1529`),
`findAndReplace` (`:1234`) and `replace` (`:2201`); `doUpdate` (`:1800-1811`) runs
`increaseVersionForUpdateIfNecessary` and nothing else. The engine mutates runs through **36 `Update`-based
writes** (23 `findAndModify`, 13 `updateFirst` — `WorkflowRunStateHelper` 12, `TaskRunService` 14,
`OutboxDispatcher` 3, `ScheduleService` 4, `DispatcherService` 1, `TaskExecutionService` 1, `WorkflowService` 1),
so `@CreatedDate` is safe (all 6 hand-stamped creation sites end in `repository.save`) but `@LastModifiedDate`
would be wrong on every CAS-mutated entity. `AuditEntity` (`core/audit`) is the audit-log feature
(`AuditInterceptor`/`AuditQueryService`), not Spring Data auditing — unrelated to this section.

### Before (verbatim)

`service-core/src/main/java/io/boomerang/core/TokenService.java:622-634`
```java
    TokenEntity tokenEntity = new TokenEntity();
    tokenEntity.setCreationDate(new Date());
    tokenEntity.setDescription("Generated User Session Token");
    ...
    tokenEntity = tokenRepository.save(tokenEntity);
```
`service-core/src/main/java/io/boomerang/core/entity/TokenEntity.java:28` (same initialiser on 10 more entities)
```java
  private Date creationDate = new Date();
```
The one creation path that goes through an `Update` — `service-core/src/main/java/io/boomerang/dispatcher/DispatcherService.java:78-86`
```java
        mongoTemplate.findAndModify(
            Query.query(
                Criteria.where("name").is(request.getName()).and("host").is(request.getHost())),
            new Update()
                ...
                .setOnInsert("creationDate", new Date()),
            new FindAndModifyOptions().upsert(true).returnNew(true),
            DispatcherEntity.class);
```

| Hand-stamped creation site | Entity | Write that follows | `@CreatedDate` covers it? |
| --- | --- | --- | --- |
| `core/TokenService.java:623` | `TokenEntity` | `tokenRepository.save` `:634` | Yes (id null → `isNew`) |
| `workflow/WorkflowService.java:1740` | `WorkflowRunEntity` | `workflowRunService.run` `:1787` → `workflowRunRepository.save` (`WorkflowRunService.java:755`) | Yes |
| `workflow/WorkflowRunService.java:910` (retry clone) | `WorkflowRunEntity` | `setId(null)` `:913`, `save` `:931` | Yes — the clone is new |
| `engine/TaskExecutionService.java:973` | `ActionEntity` | `actionRepository.save` `:1003` | Yes |
| `engine/DAGUtility.java:127` | `TaskRunEntity` | `taskRunRepository.save` `:254` | Yes |
| `workflow/WorkflowTemplateService.java:255` | `WorkflowTemplateEntity` | `setId(null)` only when `!replace` (`:249`); `save` `:304` | **Partly** — with `replace=true` the id is kept, the entity is not new, and `creationDate` is NOT re-stamped (today it is) |
| `dispatcher/DispatcherService.java:83` (`setOnInsert`) | `DispatcherEntity` | `findAndModify(upsert)` | **No** — callbacks never fire on `Update` |

### After

`service-core/src/main/java/io/boomerang/core/config/MongoAuditingConfiguration.java` (new)
```java
package io.boomerang.core.config;

import io.boomerang.core.security.IdentityService;
import io.boomerang.core.model.Token;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.mongodb.config.EnableMongoAuditing;

@Configuration
@EnableMongoAuditing(auditorAwareRef = "flowAuditorAware")
public class MongoAuditingConfiguration {

  /** Null-safe on purpose: getCurrentPrincipal() dereferences the identity (IdentityService.java:40-41). */
  @Bean
  AuditorAware<String> flowAuditorAware(IdentityService identityService) {
    return () -> Optional.ofNullable(identityService.getCurrentIdentity()).map(Token::getPrincipal);
  }
}
```
`TokenEntity.java:28` → `@CreatedDate private Date creationDate;` and delete `TokenService.java:623`. `Date` is a
supported target: `CurrentDateTimeProvider` yields `LocalDateTime` and `DefaultAuditableBeanWrapperFactory:56`
registers `Jsr310Converters` (`LocalDateTime` → `Date`), so no entity field changes type and the stored BSON
`date` is identical to today's. Under `flow.security.enabled=false` no `AuthenticationFilter` runs, so
`getCurrentIdentity()` returns `null` (`IdentityService.java:55-57`), the auditor is `Optional.empty()`, and
`AuditingHandlerSupport.touchAuditor` returns without writing (`:155-158`): a `@CreatedBy` field stays `null`.
To record the ruled virtual admin instead, `.or(() -> Optional.of(UnauthenticatedGlobalToken.PRINCIPAL))`.

### Blast radius

| Change | Files | Notes |
| --- | --- | --- |
| `@CreatedDate` on `creationDate` | 15 entities (8 `service-core`, 7 `lib-common`) | 11 drop a `= new Date()` initialiser; 4 (`ActionEntity`, `TaskRunEntity`, `WorkflowRunEntity`, `WorkflowTemplateEntity`) gain the annotation only |
| Delete hand stamps | 6 service lines | `DispatcherService.java:83` `setOnInsert` MUST stay |
| Config | 1 new class | No `MongoTemplate` bean exists to conflict (`MongoConfiguration.java` only sets `MapKeyDotReplacement`) |
| `$currentDate` / `.currentDate(` | 0 uses repo-wide | Nothing server-side changes for DocumentDB/Cosmos |
| `service-loader` seeds | 48 `creationDate` writes, 0 via `MongoTemplate` | Flamingock writes through the driver — unaffected, still hand-stamped |

### Behavioural differences and risks

| Item | Today | With auditing | Risk |
| --- | --- | --- | --- |
| `WorkflowTemplateService.apply(replace=true)` | re-stamps `creationDate` `:255` | keeps the original (entity not new) | Behaviour change; arguably the correct one — MUST be ruled, not slipped in |
| `@LastModifiedDate` on `WorkflowRunEntity`/`TaskRunEntity`/`WorkflowScheduleEntity`/`WorkflowEntity` | n/a | stamped on `save` only; the 36 CAS writes leave it stale | MUST NOT add unless every `Update` also `.currentDate("lastModified")` — 0 do today |
| `@CreatedBy` under security-off | n/a | `null` (or the virtual admin if mapped) | Decide before the `SecurityInterceptor` flip; otherwise rows minted now carry no actor |
| Free-tier / DocumentDB / Cosmos | works | works — no server feature used | None |

### Recommendation
MAY apply `@CreatedDate` (6 stamps + 11 initialisers removed, 1 config class); MUST NOT add `@LastModifiedDate`
to any CAS-mutated entity; `@CreatedBy` SHOULD wait for the security-off identity ruling. Biggest risk: the
`WorkflowTemplateService.apply(replace=true)` re-stamp silently stops.

## A10 — Pageable + correct totals

**BLUF.** All 9 `PageableExecutionUtils.getPage` sites (not 8) report a wrong `totalElements` whenever a page is
full — 4 pass `list.size()` and 5 pass `mongoTemplate.count(query, …)` on the SAME `query.with(pageable)`, and
`MongoTemplate.count` applies the query's `limit`/`skip` to the count (`QueryOperations.CountContext.getCountOptions`,
`spring-data-mongodb-5.1.0-sources.jar` `QueryOperations.java:633-638`). Both variants therefore cap the total at
`limit`: 100 matching runs, `?page=0&limit=10` → `totalElements=10`, `totalPages=1`. `PageableExecutionUtils.getPage`
only masks this on partial pages (`spring-data-commons-4.1.0` `PageableExecutionUtils.java:60-68`: a short page
derives the total itself and never calls the supplier). The fix is the pattern spring-data-mongodb's own repository
executor uses (`MongoQueryExecution.PagedExecution`, `:152-156`): `Query.of(query).skip(-1).limit(-1)`. Adopting
the `Pageable` argument resolver is a separate, wire-visible change: `limit` → `size` is one property, but
`order=ASC|DESC` has no Spring equivalent.

### Before (verbatim)

`service-core/src/main/java/io/boomerang/workflow/WorkspaceWorkflowRunControllerV2.java:105-117`
```java
      @Parameter(name = "limit", description = "Result Size", example = "10", required = true)
          @RequestParam(required = false)
          Optional<Integer> limit,
      @Parameter(name = "page", description = "Page Number", example = "0", required = true)
          @RequestParam(defaultValue = "0")
          Optional<Integer> page,
      @Parameter(
              name = "order",
              description = "Ascending (ASC) or Descending (DESC) sort order on creationDate",
              example = "ASC",
              required = true)
          @RequestParam(defaultValue = "ASC")
          Optional<Direction> order,
```
`service-core/src/main/java/io/boomerang/workflow/WorkflowRunService.java:489-493` and `:567-585`
```java
    Pageable pageable = Pageable.unpaged();
    final Sort sort = Sort.by(new Order(querySort.orElse(Direction.ASC), "creationDate"));
    if (queryLimit.isPresent()) {
      pageable = PageRequest.of(queryPage.get(), queryLimit.get(), sort);
    }
```
```java
    Query query = new Query(allCriteria);
    if (queryLimit.isPresent()) {
      query.with(pageable);
    } else {
      query.with(sort);
    }

    List<WorkflowRunEntity> wfRunEntities = mongoTemplate.find(query, WorkflowRunEntity.class);
    ...
    Page<WorkflowRun> pages = PageableExecutionUtils.getPage(wfRuns, pageable, () -> wfRuns.size());
```

| Site | `query.with(pageable)`? | Total supplier today | Wrong when |
| --- | --- | --- | --- |
| `workflow/WorkflowRunService.java:585` | `:569` | `wfRuns.size()` | page full |
| `workflow/WorkflowService.java:1384` | `:1360` | `workflows.size()` | page full |
| `workflow/WorkflowTemplateService.java:142` | `:130` + again in `find` | `wfTemplates.size()` | page full |
| `workflow/TaskService.java:844` | `:825` + again in `find` | `tasks.size()` | page full |
| `schedule/ScheduleService.java:164` | `:152` + again in `find` | `mongoTemplate.count(query, …)` — limited/skipped | page full |
| `core/UserService.java:410` | `:397` | `mongoTemplate.count(query, …)` — limited/skipped | page full |
| `core/TokenService.java:506` | `:490` | `mongoTemplate.count(query, ActionEntity.class)` — **wrong entity class too** (counts `actions`, not `tokens`) | always |
| `workspace/WorkspaceService.java:463` | `:449` | `mongoTemplate.count(query, …)` — limited/skipped | page full |
| `workflow/ActionService.java:257` | `:245` + again in `find` | `mongoTemplate.count(query, …)` — limited/skipped | page full |

### After

(1) Minimal fix, one line per site, no contract change — `WorkflowRunService.java:585`:
```java
    Page<WorkflowRun> pages =
        PageableExecutionUtils.getPage(
            wfRuns,
            pageable,
            () -> mongoTemplate.count(Query.of(query).skip(-1).limit(-1), WorkflowRunEntity.class));
```
Semantics verified in `Query.java`: `of()` copies `skip` and `limit` from the source (`:760-761`), so the reset is
required; `limit(int)` maps `<= 0` to `Limit.unlimited()` (`:177-178`); `skip(-1)` is stored raw and `CountContext`
applies skip only `if (query.getSkip() > 0)` (`QueryOperations.java:637`). `TokenService.java:507` MUST also change
`ActionEntity.class` → `TokenEntity.class`. The A11 shared Criteria builder could carry a `countAll(Query, Class)`
helper so the 9 sites do not each repeat the reset.

(2) Framework form — `Pageable` argument resolver. `DataWebAutoConfiguration`
(`spring-boot-data-commons-4.1.0.jar`, `org.springframework.boot.data.autoconfigure.web`) is active today:
`@ConditionalOnWebApplication(SERVLET)` + `@ConditionalOnClass(PageableHandlerMethodArgumentResolver, WebMvcConfigurer)`
+ `@ConditionalOnMissingBean`, pulled in by `spring-boot-starter-data-mongodb` → `spring-boot-data-mongodb` →
`spring-boot-data-commons` (`spring-boot-data-mongodb-4.1.0.pom:46`), and `application.properties:9` excludes only
`ServletWebSecurityAutoConfiguration`. A `Pageable` parameter therefore already resolves; nothing to register.
```java
  // WorkspaceWorkflowRunControllerV2.query(...) — replaces limit/page/order
  @PageableDefault(size = 20, sort = "creationDate", direction = Sort.Direction.DESC) Pageable pageable,
```
```properties
# application.properties — keep the existing parameter name; Spring's default is `size`
spring.data.web.pageable.size-parameter=limit
spring.data.web.pageable.max-page-size=2000
```
Wire mapping for `client-web` (`client-web/src/Features/Activity/Activity.tsx:114` builds
`{ order, page, limit, sort, … }` with `DEFAULT_ORDER = "DESC"` `:32`, `DEFAULT_SORT = "creationDate"` `:35`;
`ActivityTable.tsx:83-88` toggles `order`):

| Today | Spring `Pageable` | Change owner |
| --- | --- | --- |
| `page=0` | `page=0` (`one-indexed-parameters=false`) | none |
| `limit=10` | `size=10`, or `limit=10` via `size-parameter=limit` | property |
| `sort=creationDate&order=DESC` | `sort=creationDate,desc` (`SortHandlerMethodArgumentResolverSupport`, delimiter `,` `:53`) | **client-web** — 15 files reference `order`; 9 `*ControllerV2` declare `Optional<Direction> order` |
| `limit` absent → `Pageable.unpaged()` (all rows) | `default-page-size=20` | **client-web** — every caller that omits `limit` and expects all rows |

### Blast radius

| Change | Sites | Contract |
| --- | --- | --- |
| Count fix | 9 `getPage` suppliers + 1 entity-class fix | none — `totalElements`/`totalPages` become correct; `client-web` reads both (25 references, e.g. `ActivityTable.tsx:111`) |
| `Pageable` adoption | 9 controllers, 9 services, `client-web` 15 files | `order` param retired; `sort=field,dir` |

### Behavioural differences and risks

| Item | Today | With `Pageable` resolver | Risk |
| --- | --- | --- | --- |
| Missing `limit` | unpaged — full result | 20 rows | Silent truncation for every "give me everything" call |
| `limit=100000` | honoured | clamped to `max-page-size` (2000 default) | Acceptable; document it |
| `sort` | ignored — hard-coded `creationDate` (`WorkflowRunService.java:490`) | any property honoured | Unindexed sorts on `workflow_runs`; the only compound indexes are `status_phase*` |
| `page=-1` | `PageRequest.of` throws → 500 | clamped to 0 | Improvement |
| OpenAPI | 3 explicit params | springdoc renders `Pageable` only with `@ParameterObject` | Docs regress if forgotten |

### Recommendation
MUST fix the 9 counts now (plus `TokenService.java:507`'s entity class) — no wire change. `Pageable` adoption
SHOULD wait for a coordinated `client-web` change that sends `sort=creationDate,desc` and always sends `limit`.
Biggest risk: absent-`limit` = "all rows" today, 20 rows after.

## A16 — Entity↔model mapping

**BLUF.** 47 `BeanUtils.copyProperties` sites (39 `service-core`, 8 `lib-common`, 0 elsewhere). The public run
models exclude execution state ONLY because the model classes do not declare the properties — `copyProperties`
matches getter/setter by name and type and silently skips the rest. Nothing checks the diff: a renamed field on
either side becomes a `null` on the wire with no compile or test failure. `PublicRunModelSerialisationTest`
(`service-core/src/test/java/io/boomerang/common/PublicRunModelSerialisationTest.java`) serialises an EMPTY
`new WorkflowRun()` and asserts 11 forbidden names are absent — it pins what the model declares, not what the copy
does. A MapStruct mapper with `unmappedSourcePolicy = ERROR` makes the entity-only set an explicit, reviewed list
that fails compilation when a new execution-state field appears on the entity; `unmappedTargetPolicy = ERROR`
catches the rename direction.

### Before (verbatim)

`service-core/src/main/java/io/boomerang/workflow/ConvertUtil.java:35-52`
```java
  public static <E, M> M entityToModel(E entity, Class<M> modelClass) {
    if (Objects.isNull(entity) || Objects.isNull(modelClass)) {
      throw new BoomerangException(BoomerangError.DATA_CONVERSION_FAILED);
    }

    try {
      M model = modelClass.getDeclaredConstructor().newInstance();
      BeanUtils.copyProperties(entity, model);
      return model;
    } catch (NoSuchMethodException
        ...
```
`lib-common/src/main/java/io/boomerang/common/model/TaskRun.java:55-57`
```java
  public TaskRun(TaskRunEntity entity) {
    BeanUtils.copyProperties(entity, this);
  }
```

| Group | Sites | Examples |
| --- | --- | --- |
| Model constructor `copyProperties(entity, this)` | 20 | `Token.java:39`, `User.java:16,20`, `WorkspaceSummary.java:24,29`, `TaskRun.java:56`, `WorkflowTemplate.java:85`, `WorkflowSchedule.java:50,54`, `Action.java:17` |
| Service-side entity → model | 10 | `ConvertUtil.java:27,28,42`, `TaskService.java:901,902`, `TokenService.java:519`, `ParameterService.java:49,65,114`, `IntegrationService.java:66` |
| Request/model → entity | 8 | `ScheduleService.java:212,359`, `ParameterService.java:93`, `TaskEntity.java:39`, `TaskRevisionEntity.java:41`, `UserService.java:223,230`, `WorkspaceService.java:179` |
| Model ↔ model / external | 9 | `TektonConverter.java:75,170,180`, `WorkflowService.java:455,479,1112`, `WorkflowCanvas.java:38`, `UserService.java:195,286` |

`ConvertUtil.entityToModel` is generic in signature but has exactly 2 target types across its 14 call sites:
`WorkflowRun` × 13 (`WorkflowRunService` × 10, `DispatcherService.java:136,143`, `EventFactory.java:64`) and
`WorkflowRunSummary` × 1 (`WorkflowRunService.java:668`). `new TaskRun(entity)` has 7 sites
(`DispatcherService.java:210,223`, `EventFactory.java:86`, `TaskRunService.java:692,725,784`, `WorkflowRunService.java:1040`).

Field-set diff today (`lib-common/.../entity/WorkflowRunEntity.java:32-81` vs `model/WorkflowRun.java:40-68`;
`TaskRunEntity.java:37-81` vs `TaskRun.java:30-52`):

| Pair | Entity-only (dropped by the copy) | Model-only (never populated by the copy) |
| --- | --- | --- |
| `WorkflowRunEntity → WorkflowRun` | `statusOverride`, `claim`, `timeoutAt`, `pauseRequestedAt`, `retryCount` (5) | `workflowName`, `workflowDisplayName`, `tasks` (3) — set afterwards by `updateWorkflowDetails` `:947-954`; `paused` IS copied, via the entity's derived getter `isPaused()` `:66-68` |
| `TaskRunEntity → TaskRun` | `preApproved`, `decisionValue`, `dependencies`, `claim`, `timeoutAt`, `retry`, `waitUntil` (7) | `workflowName` (1) |

### After

| Option | Fits when | Cost/risk | Recommend |
| --- | --- | --- | --- |
| (a) Keep `BeanUtils`; add one test pinning the exact entity-only / model-only sets via `BeanUtils.getPropertyDescriptors` | Now — zero runtime change | Guards drift only at test time; 1 file | **Yes, now** |
| (b) MapStruct `1.6.3` (`org.mapstruct:mapstruct` + `mapstruct-processor`; latest GA — `1.7.0.Beta2` 2026-06-27 is beta) with `lombok-mapstruct-binding:0.2.0`; Lombok `1.18.46` is Boot-managed (`spring-boot-dependencies-4.1.0.pom:130`) | After Q-202 decides where entities live | New `annotationProcessorPaths` block in `lib-common`/`service-core` poms (only `service-loader/pom.xml:77` has one today); MapStruct has no published Java 25 statement — MUST prove with one compile | Later, for the 2 run pairs only |
| (c) Hand-written `Converter<Entity, Model>` beans | Never for 47 sites | Same silent-drift problem as (a) with more code | No |

(b) — `service-core/src/main/java/io/boomerang/workflow/WorkflowRunMapper.java` (new; both policies ERROR):
```java
package io.boomerang.workflow;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.model.WorkflowRun;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(
    componentModel = "spring",
    unmappedTargetPolicy = ReportingPolicy.ERROR,
    unmappedSourcePolicy = ReportingPolicy.ERROR)
public interface WorkflowRunMapper {

  /** Execution state stays on the entity - every name here is asserted absent by PublicRunModelSerialisationTest. */
  @BeanMapping(
      ignoreUnmappedSourceProperties = {
        "claim", "timeoutAt", "pauseRequestedAt", "retryCount", "statusOverride"
      })
  @Mapping(target = "workflowName", ignore = true) // set by WorkflowRunService.updateWorkflowDetails
  @Mapping(target = "workflowDisplayName", ignore = true)
  @Mapping(target = "tasks", ignore = true)
  WorkflowRun toModel(WorkflowRunEntity entity);
}
```
`paused` maps automatically (`isPaused()` → `setPaused`). Call sites: `WorkflowRunService.java:580`
`ConvertUtil.entityToModel(e, WorkflowRun.class)` → `workflowRunMapper.toModel(e)` (10 in that class, 2 in
`DispatcherService`, 1 in `EventFactory` — the mapper is injected, so `EventFactory` and `DispatcherService` gain a
constructor dependency). A `TaskRunMapper` with the 7-name source list replaces `new TaskRun(entity)` at 7 sites;
the `TaskRun(TaskRunEntity)` constructor is deleted so `lib-common` no longer depends on `BeanUtils`.

(a) — `service-core/src/test/java/io/boomerang/common/RunModelFieldSetTest.java` (new)
```java
  @Test
  void workflowRunEntityOnlyPropertiesAreExactlyTheExecutionState() {
    Set<String> entityOnly = readable(WorkflowRunEntity.class);
    entityOnly.removeAll(writable(WorkflowRun.class));
    assertThat(entityOnly)
        .containsExactlyInAnyOrder("statusOverride", "claim", "timeoutAt", "pauseRequestedAt", "retryCount");
  }
  // readable()/writable() = BeanUtils.getPropertyDescriptors(...) filtered on getReadMethod()/getWriteMethod(), minus "class"
```

### Blast radius

| Change | Files | Notes |
| --- | --- | --- |
| (a) | 1 new test | none at runtime |
| (b) | 2 mappers, 3 poms, 21 call sites (14 + 7), `ConvertUtil.entityToModel` deleted, `TaskRun(TaskRunEntity)` deleted | The other 33 `copyProperties` sites are untouched |

### Behavioural differences and risks

| Item | `BeanUtils` today | MapStruct | Risk |
| --- | --- | --- | --- |
| Collections/maps | reference copy — model and entity share `params`, `results`, `labels`, `annotations` | new `LinkedList`/`HashMap` per call | `updateWorkflowDetails` `:957-959` removes 3 `boomerang.io/*-params` annotations from `wfRun.getAnnotations()`, which today ALSO strips them from the in-memory entity; after MapStruct the entity is untouched. No save follows, so today's aliasing is harmless — but every mutate-the-model site MUST be audited before switching |
| Unknown field on either side | silent `null` | compile error | The intended gain |
| Build | Lombok from classpath | ordered `annotationProcessorPaths` (lombok → binding → mapstruct) | Misorder = "unknown property" compile errors — loud, not silent |
| `PublicRunModelSerialisationTest` | pins model declarations | unchanged and still needed — MapStruct checks the mapping, not Jackson | none |

### Recommendation
SHOULD do (a) now — one test, no runtime change, closes the silent-drift gap for both run pairs. (b) MAY follow for
`WorkflowRun`/`TaskRun` only, after Q-202 settles the entity package. Biggest risk of (b): the collection
reference-aliasing change — callers that mutate a model list or map today also mutate the entity.
