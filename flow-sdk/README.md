# Flow SDK

Use Boomerang Flow from Java. This library is the public API (`/api/v2`) as typed calls: submit, read, cancel
and retry workflow runs; query runs and task runs by label; read Insights. It is the twin of `dispatcher-sdk`:
that library lets a program serve Flow's work, this one lets a program use Flow. The routes and models are
`contracts/api-v2.yaml`, which the server is tested against and this library's models round-trip; the rules
every route follows are `specifications/api-contract.md`. This page is the how-to.

## Get it

Published to this repository's GitHub Packages registry on every product tag, as `io.boomerang:flow-sdk` with
the product's version. Reading GitHub Packages needs a token with `read:packages`.

```xml
<repository>
  <id>boomerang-flow</id>
  <url>https://maven.pkg.github.com/boomerang-io/flow</url>
</repository>

<dependency>
  <groupId>io.boomerang</groupId>
  <artifactId>flow-sdk</artifactId>
  <version>5.0.0</version>
</dependency>
```

It depends on `spring-web` (for `RestClient`) and Jackson, and nothing else: no Spring Boot.

## Use it

```java
Flow flow = Flow.connect("https://flow.example.com", token);   // a workspace or global Flow token
Runs runs = flow.runs("my-workspace");

WorkflowRun run =
    runs.submit("analysis", SubmitRequest.of(List.of(RunParam.of("depth", 3)), Map.of("team.io/batch", "b-7")));

run = runs.get(run.id());                                       // with its task runs
if (run.status() == RunStatus.failed) {
  run = runs.retry(run.id(), Retry.failed().param("depth", 5)); // a new run; Retry.all(), Retry.nodes("review")
}
runs.cancel(run.id());

Page<WorkflowRun> page =
    runs.query(RunQuery.all().label("team.io/batch", "b-7").statuses(RunStatus.running).page(0, 50));
page.total();                                                   // every match, over all pages
page.next();                                                    // Optional<Page<WorkflowRun>>
runs.count(RunQuery.all().label("team.io/batch", "b-7"));       // by status

flow.taskRuns("my-workspace").query(TaskRunQuery.all().label("team.io/batch", "b-7").tasks("review"));

Insights insights = flow.insights("my-workspace");
insights.summary(InsightsQuery.period(t0, t1));
insights.tasks(InsightsQuery.period(t0, t1).groupByLabel("boomerang.io/item").bucket(Bucket.day));
insights.workflow("analysis", InsightsQuery.all());             // the server's default: the last 90 days
```

Every call returns the server's answer or throws `FlowApiException`, which carries the server's error body:
`code()`, `reason()` (such as `WORKFLOWRUN_INVALID_REF`), `error().message()` and `status()`. A server that
cannot be reached throws Spring's `ResourceAccessException`. The library retries nothing and sets no policy of
its own: hand in the host's `RestClient` and its proxy, trust store and timeouts apply:

```java
Flow.builder().baseUrl(url).token(token).restClient(hostRestClient).build();
```

The run models are what the public API answers and nothing of the engine's execution state; `toString` on a
run, task run, param or spec never prints a value that may be a secret. A status, phase or task type the
server added after this library was built reads as `unknown`.

## Not in this release

Workflow authoring, tokens, settings, schedules, the callback and webhook routes, and log streaming. Each is
added behind the same contract test when an embedder needs it.
