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
  @ParamName private String name;                    // AbstractParam (RunParam/ParamSpec inherit)
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

_(pending — filled by a separate pass)_

## A10 — Pageable + correct totals

_(pending — filled by a separate pass)_

## A16 — Entity↔model mapping

_(pending — filled by a separate pass)_
