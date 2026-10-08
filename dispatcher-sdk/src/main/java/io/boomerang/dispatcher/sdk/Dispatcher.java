package io.boomerang.dispatcher.sdk;

import io.boomerang.dispatcher.sdk.model.DispatcherRegistrationRequest;
import io.boomerang.dispatcher.sdk.model.TaskRun;
import io.boomerang.dispatcher.sdk.model.WorkflowRun;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;

/**
 * A running dispatcher: registers with the engine, long-polls for work, runs each task through
 * its {@link TaskHandler}, renews the lease of every task in flight, and reports how each ended.
 *
 * <pre>{@code
 * Dispatcher dispatcher = Dispatcher.builder()
 *     .engine("http://flow:7700", token)
 *     .name("my-dispatcher")
 *     .taskTypes(List.of("template", "custom"))
 *     .tasks(handler, () -> 25)
 *     .build();
 * dispatcher.start();
 * }</pre>
 *
 * <p>{@link #start} returns at once: registration is retried with backoff until the engine
 * answers, and polling begins once it has. {@link #stop} stops polling, waits up to the drain
 * timeout for the tasks in flight to finish and report, and keeps their leases renewed while it
 * waits.
 */
public final class Dispatcher implements AutoCloseable {

  /** How often the lease of every task in flight is renewed; the engine holds a lease 90 s. */
  public static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(30);

  /** How long {@link #stop} waits for the tasks in flight. */
  public static final Duration DEFAULT_DRAIN_TIMEOUT = Duration.ofSeconds(10);

  private static final Log LOGGER = LogFactory.getLog(Dispatcher.class);

  private final DispatcherClient client;
  private final RestClient artifactHttp;
  private final DispatcherRegistrationRequest registration;
  private final Set<String> taskTypes;
  private final List<Pool> pools;
  private final WorkflowHandler workflowHandler;
  private final Duration heartbeatInterval;
  private final Duration drainTimeout;
  private final Backoff registerBackoff;
  private final Backoff endBackoff;
  private final Duration idlePollSpacing;

  private final Map<String, TaskContext> running = new ConcurrentHashMap<>();
  private final ExecutorService work =
      Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("dispatcher-work-", 0).factory());
  private final List<QueuePoller<?>> pollers = new ArrayList<>();

  private ScheduledExecutorService heartbeats;
  private Thread starter;
  private volatile String id;
  private volatile boolean stopped;
  private volatile boolean abandoned;

  private Dispatcher(Builder builder) {
    this.client = builder.client;
    this.artifactHttp = builder.artifactHttp;
    this.registration =
        new DispatcherRegistrationRequest(
            builder.name, builder.host, new ArrayList<>(builder.taskTypes));
    this.registration.setVersion(builder.version);
    this.taskTypes = Set.copyOf(builder.taskTypes);
    this.pools = List.copyOf(builder.pools);
    this.workflowHandler = builder.workflowHandler;
    this.heartbeatInterval = builder.heartbeatInterval;
    this.drainTimeout = builder.drainTimeout;
    this.registerBackoff = builder.registerBackoff;
    this.endBackoff = builder.endBackoff;
    this.idlePollSpacing = builder.idlePollSpacing;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Register in the background, then poll. Returns at once; a second call does nothing. */
  public synchronized void start() {
    if (starter == null && !stopped) {
      starter =
          Thread.ofVirtual()
              .name("dispatcher-register")
              .start(
                  () -> {
                    if (register()) {
                      startPolling();
                    }
                  });
    }
  }

  /**
   * Stop polling and wait up to the drain timeout for the tasks in flight to finish and report,
   * renewing their leases meanwhile. A task still running after that is interrupted and reports
   * nothing; its lease lapses and the engine hands it out again.
   */
  public void stop() {
    synchronized (this) {
      if (stopped) {
        return;
      }
      stopped = true;
      if (starter != null) {
        starter.interrupt();
      }
      pollers.forEach(QueuePoller::stop);
    }
    work.shutdown();
    try {
      if (!work.awaitTermination(drainTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
        LOGGER.warn("Dispatcher stopping with tasks still in flight: " + inFlight());
        abandoned = true;
        work.shutdownNow();
      }
    } catch (InterruptedException e) {
      abandoned = true;
      work.shutdownNow();
      Thread.currentThread().interrupt();
    } finally {
      synchronized (this) {
        if (heartbeats != null) {
          heartbeats.shutdownNow();
        }
      }
    }
  }

  @Override
  public void close() {
    stop();
  }

  /** The id the engine registered this dispatcher under, or null until it has. */
  public String id() {
    return id;
  }

  public boolean isRegistered() {
    return id != null;
  }

  public DispatcherClient client() {
    return client;
  }

  /** Return the task run ids in flight across every pool. */
  public List<String> inFlight() {
    Set<String> ids = new LinkedHashSet<>();
    pools.forEach(pool -> ids.addAll(pool.inFlight().ids()));
    return List.copyOf(ids);
  }

  /** Renew the lease of every task in flight; a missed beat is the engine's signal, never fatal. */
  void beat() {
    List<String> ids = inFlight();
    if (id == null || ids.isEmpty()) {
      return;
    }
    try {
      client.heartbeat(id, ids);
    } catch (RuntimeException e) {
      LOGGER.warn("Dispatcher heartbeat failed: " + e.getMessage());
    }
  }

  // Retried until the engine answers, so a dispatcher started before its engine waits for it.
  boolean register() {
    Duration delay = registerBackoff.initial();
    for (int attempt = 1; !stopped; attempt++) {
      try {
        String registered = client.register(registration);
        if (registered != null && !registered.isBlank()) {
          id = registered;
          LOGGER.info(
              "Dispatcher "
                  + registration.getName()
                  + " ("
                  + registration.getHost()
                  + ") registered as "
                  + id
                  + " for task types "
                  + registration.getTaskTypes()
                  + ".");
          return true;
        }
        LOGGER.warn("Dispatcher registration attempt " + attempt + " was answered with no id.");
      } catch (RuntimeException e) {
        LOGGER.warn(
            "Dispatcher registration attempt "
                + attempt
                + " failed, retrying in "
                + delay.toMillis()
                + " ms: "
                + e.getMessage());
      }
      if (!Backoff.sleep(delay)) {
        return false;
      }
      delay = registerBackoff.next(delay);
    }
    return false;
  }

  private synchronized void startPolling() {
    if (stopped) {
      return;
    }
    for (Pool pool : pools) {
      TaskRunner runner =
          new TaskRunner(
              client,
              this::id,
              pool.handler(),
              pool.inFlight(),
              running,
              task -> task.getType() != null && taskTypes.contains(task.getType().name()),
              task -> new ArtifactTransfer(artifactHttp, task),
              work,
              endBackoff,
              () -> abandoned);
      pollers.add(
          new QueuePoller<TaskRun>(
              pool.name(),
              () -> client.pollTasks(id, pool.inFlight().free(), pool.filter()),
              runner::accept,
              idlePollSpacing));
    }
    if (workflowHandler != null) {
      WorkflowRunner runner = new WorkflowRunner(client, workflowHandler, work);
      pollers.add(
          new QueuePoller<WorkflowRun>(
              "workflows", () -> client.pollWorkflows(id), runner::accept, idlePollSpacing));
    }
    pollers.forEach(QueuePoller::start);
    if (heartbeatInterval != null && heartbeatInterval.isPositive()) {
      heartbeats =
          Executors.newSingleThreadScheduledExecutor(
              Thread.ofVirtual().name("dispatcher-heartbeat").factory());
      heartbeats.scheduleWithFixedDelay(
          this::beat,
          heartbeatInterval.toMillis(),
          heartbeatInterval.toMillis(),
          TimeUnit.MILLISECONDS);
    }
  }

  /** One task poll: what it claims, how much it may have in flight, and what runs its tasks. */
  record Pool(String name, TaskFilter filter, InFlightTasks inFlight, TaskHandler handler) {}

  public static final class Builder {

    private DispatcherClient client;
    private String engineUrl;
    private String token;
    private RestClient http;
    private RestClient artifactHttp;
    private String name;
    private String host;
    private Integer version;
    private final List<String> taskTypes = new ArrayList<>();
    private final List<Pool> pools = new ArrayList<>();
    private WorkflowHandler workflowHandler;
    private Duration heartbeatInterval = DEFAULT_HEARTBEAT_INTERVAL;
    private Duration drainTimeout = DEFAULT_DRAIN_TIMEOUT;
    private Backoff registerBackoff = Backoff.DEFAULT;
    private Backoff endBackoff = Backoff.DEFAULT;
    private Duration idlePollSpacing = QueuePoller.IDLE_POLL_SPACING;

    private Builder() {}

    /** Call the engine at {@code engineUrl}, sending {@code token} as a bearer token. */
    public Builder engine(String engineUrl, String token) {
      this.engineUrl = engineUrl;
      this.token = token;
      return this;
    }

    /**
     * Use {@code http}'s transport - proxy, TLS, timeouts - for every call. Its read timeout MUST
     * exceed 30 s. Defaults to {@link DispatcherClient#defaultHttp()}.
     */
    public Builder http(RestClient http) {
      this.http = http;
      return this;
    }

    /** Use a client already built, in place of {@link #engine} and {@link #http}. */
    public Builder client(DispatcherClient client) {
      this.client = client;
      return this;
    }

    /**
     * Use {@code artifactHttp} for artifact transfers, which reach the artifact store rather than
     * the engine. Defaults to {@link #http}, without the engine token.
     */
    public Builder artifactHttp(RestClient artifactHttp) {
      this.artifactHttp = artifactHttp;
      return this;
    }

    /** The name to register; with the host it identifies the dispatcher across restarts. */
    public Builder name(String name) {
      this.name = name;
      return this;
    }

    /** The host to register. Defaults to this machine's host name. */
    public Builder host(String host) {
      this.host = host;
      return this;
    }

    public Builder version(Integer version) {
      this.version = version;
      return this;
    }

    /**
     * The task types to register; the engine hands this dispatcher only these, and an order to run
     * any other type is skipped.
     */
    public Builder taskTypes(Collection<String> taskTypes) {
      this.taskTypes.clear();
      this.taskTypes.addAll(taskTypes);
      return this;
    }

    /** Run every registered type with {@code handler}, with no cap on tasks in flight. */
    public Builder tasks(TaskHandler handler) {
      return tasks(handler, () -> 0);
    }

    /**
     * Run every registered type with {@code handler}, with at most {@code maxInFlight} tasks in
     * flight; 0 or less is no cap.
     */
    public Builder tasks(TaskHandler handler, IntSupplier maxInFlight) {
      return taskPool("tasks", TaskFilter.none(), maxInFlight, handler);
    }

    /**
     * Add one task poll: it claims what {@code filter} matches, keeps at most {@code maxInFlight}
     * in flight - read on every poll, so it can change at runtime - and runs them with {@code
     * handler}. Each pool polls on its own thread.
     */
    public Builder taskPool(
        String name, TaskFilter filter, IntSupplier maxInFlight, TaskHandler handler) {
      pools.add(new Pool(name, filter, new InFlightTasks(maxInFlight), handler));
      return this;
    }

    /** Poll the workflow queue and provision each run with {@code handler}. */
    public Builder workflows(WorkflowHandler handler) {
      this.workflowHandler = handler;
      return this;
    }

    /** How often to renew leases; zero or null sends no heartbeat. */
    public Builder heartbeatInterval(Duration heartbeatInterval) {
      this.heartbeatInterval = heartbeatInterval;
      return this;
    }

    public Builder drainTimeout(Duration drainTimeout) {
      this.drainTimeout = drainTimeout;
      return this;
    }

    Builder backoff(Backoff registerBackoff, Backoff endBackoff) {
      this.registerBackoff = registerBackoff;
      this.endBackoff = endBackoff;
      return this;
    }

    Builder idlePollSpacing(Duration idlePollSpacing) {
      this.idlePollSpacing = idlePollSpacing;
      return this;
    }

    public Dispatcher build() {
      Assert.hasText(name, "name must not be empty");
      Assert.state(
          !pools.isEmpty() || workflowHandler != null,
          "a dispatcher needs a task handler or a workflow handler");
      RestClient transport = (http != null) ? http : DispatcherClient.defaultHttp();
      if (client == null) {
        Assert.hasText(engineUrl, "engine URL must not be empty");
        client = new DispatcherClient(transport, engineUrl, token);
      }
      if (artifactHttp == null) {
        artifactHttp = transport;
      }
      if (host == null || host.isBlank()) {
        host = localHostName();
      }
      Assert.notNull(drainTimeout, "drainTimeout must not be null");
      return new Dispatcher(this);
    }

    private static String localHostName() {
      try {
        return InetAddress.getLocalHost().getHostName();
      } catch (UnknownHostException e) {
        String hostname = System.getenv("HOSTNAME");
        return (hostname != null && !hostname.isBlank()) ? hostname : "localhost";
      }
    }
  }
}
