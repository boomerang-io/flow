package io.boomerang.core.audit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.aspectj.lang.annotation.AfterReturning;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.aop.aspectj.AspectJExpressionPointcut;

/**
 * Guards {@link AuditInterceptor}'s AspectJ pointcuts against silent package/signature drift.
 *
 * <p>Why this exists: every pointcut is a STRING, so the compiler cannot check it. All nine
 * pointcuts referenced {@code io.boomerang.service.WorkflowService} / {@code
 * io.boomerang.service.WorkspaceService} - a package that has not existed since the flat
 * feature-package restructure (the team-scoped methods live on {@code
 * io.boomerang.api.WorkspaceWorkflowService}, the workspace ones on {@code
 * io.boomerang.workspace.WorkspaceService}). Nothing matched, so the aspect - the ONLY writer of
 * {@code AuditEntity} - silently wrote no audit records at all. The DD-01 rename even rewrote
 * {@code TeamService} to {@code WorkspaceService} inside an already-dead package string without
 * anything failing.
 *
 * <p>This test reads the expressions straight off the annotations at runtime rather than
 * duplicating them, so the test and the aspect cannot drift apart.
 */
class AuditInterceptorPointcutTest {

  /** Pulls the fully-qualified target type and method name out of an {@code execution(...)}. */
  private static final Pattern EXECUTION =
      Pattern.compile("execution\\(\\s*\\*\\s+(io\\.boomerang\\.[\\w.]+)\\.(\\w+)\\s*\\(");

  /** Pulls the {@code args(...)} binding clause, if the pointcut has one. */
  private static final Pattern ARGS = Pattern.compile("args\\(([^)]*)\\)");

  private record Advice(String adviceMethod, String expression) {}

  private static List<Advice> advices() {
    List<Advice> found = new ArrayList<>();
    for (Method m : AuditInterceptor.class.getDeclaredMethods()) {
      AfterReturning annotation = m.getAnnotation(AfterReturning.class);
      if (annotation == null) {
        continue;
      }
      String expression =
          annotation.pointcut().isBlank() ? annotation.value() : annotation.pointcut();
      found.add(new Advice(m.getName(), expression));
    }
    return found;
  }

  @Test
  void everyAdviceMethodIsDiscovered() {
    // 6 workflow + 3 workspace advices. Guards against this test silently finding nothing.
    assertFalse(advices().isEmpty(), "No @AfterReturning advice found on AuditInterceptor");
    assertTrue(
        advices().size() >= 9,
        "Expected at least 9 audit advices, found " + advices().size());
  }

  @TestFactory
  List<DynamicTest> everyPointcutMatchesARealMethod() {
    List<DynamicTest> tests = new ArrayList<>();
    for (Advice advice : advices()) {
      tests.add(
          DynamicTest.dynamicTest(
              advice.adviceMethod() + " -> " + advice.expression(),
              () -> assertPointcutMatches(advice)));
    }
    return tests;
  }

  private void assertPointcutMatches(Advice advice) throws Exception {
    Matcher matcher = EXECUTION.matcher(advice.expression());
    if (!matcher.find()) {
      fail("Could not parse an execution() clause from: " + advice.expression());
    }
    String targetClassName = matcher.group(1);
    String targetMethodName = matcher.group(2);

    Class<?> targetClass;
    try {
      targetClass = Class.forName(targetClassName);
    } catch (ClassNotFoundException ex) {
      throw new AssertionError(
          "Pointcut on "
              + advice.adviceMethod()
              + " targets a class that does not exist: "
              + targetClassName
              + ". The audited method moved - repoint the pointcut.",
          ex);
    }

    List<Method> candidates =
        Arrays.stream(targetClass.getDeclaredMethods())
            .filter(m -> m.getName().equals(targetMethodName))
            .toList();
    assertFalse(
        candidates.isEmpty(),
        "Pointcut on "
            + advice.adviceMethod()
            + " targets "
            + targetClassName
            + "."
            + targetMethodName
            + "(..), which does not exist on that class.");

    // The execution() half must select at least one real method.
    AspectJExpressionPointcut pointcut = new AspectJExpressionPointcut();
    pointcut.setExpression(
        "execution(* " + targetClassName + "." + targetMethodName + "(..))");
    List<Method> matched =
        candidates.stream().filter(m -> pointcut.matches(m, targetClass)).toList();
    assertFalse(
        matched.isEmpty(),
        "execution() clause on " + advice.adviceMethod() + " matched no method on " + targetClassName);

    // The args() half must be arity-compatible with at least one matched method, otherwise the
    // advice still never fires (this is what broke submit(): args(team, id) binds exactly two
    // arguments, but the real method takes four).
    Matcher argsMatcher = ARGS.matcher(advice.expression());
    if (!argsMatcher.find()) {
      return;
    }
    String argsClause = argsMatcher.group(1).trim();
    if (argsClause.isEmpty()) {
      return;
    }
    List<String> bindings = Arrays.stream(argsClause.split(",")).map(String::trim).toList();
    boolean open = bindings.get(bindings.size() - 1).equals("..");
    int bound = open ? bindings.size() - 1 : bindings.size();

    boolean arityOk =
        matched.stream()
            .anyMatch(
                m ->
                    open
                        ? m.getParameterCount() >= bound
                        : m.getParameterCount() == bound);
    assertTrue(
        arityOk,
        "args("
            + argsClause
            + ") on "
            + advice.adviceMethod()
            + " binds "
            + bound
            + (open ? "+" : "")
            + " argument(s), but "
            + targetClassName
            + "."
            + targetMethodName
            + " takes "
            + matched.stream().map(m -> String.valueOf(m.getParameterCount())).toList()
            + ". The advice would never fire.");
  }
}
