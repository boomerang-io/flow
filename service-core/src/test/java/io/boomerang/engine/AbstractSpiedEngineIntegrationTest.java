package io.boomerang.engine;

import io.boomerang.engine.repository.TaskRunRepository;
import io.boomerang.integrations.GitHubService;
import io.boomerang.integrations.SlackService;
import io.boomerang.workflow.repository.TaskRevisionRepository;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The shared engine context with the beans some tests observe or interrupt wrapped in Mockito
 * spies. A spy is a bean override, and every distinct set of overrides is a distinct Spring
 * context: the whole application wired again, on top of the one the base class already holds.
 * Declaring the spies here, once, means the classes that need one share a single extra context
 * instead of each paying for their own.
 *
 * <p>Spies are reset after every test, so a stub or a {@code doAnswer} in one class never reaches
 * another. A subclass that also needs its own properties (see {@code PayloadCapTest}) still gets
 * its own context, by design.
 */
public abstract class AbstractSpiedEngineIntegrationTest extends AbstractEngineIntegrationTest {

  @MockitoSpyBean protected DAGUtility dagUtility;
  @MockitoSpyBean protected TaskRunService spiedTaskRunService;
  @MockitoSpyBean protected TaskRunRepository spiedTaskRunRepository;
  @MockitoSpyBean protected TaskRevisionRepository spiedTaskRevisionRepository;
  @MockitoSpyBean protected GitHubService spiedGitHubService;
  @MockitoSpyBean protected SlackService spiedSlackService;
}
