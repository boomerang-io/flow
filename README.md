# Flow Workflow Service


The Boomerang Flow Workflow service provides the CRUD backing the front-end web tier, as well as the v1 APIs for direct consumption, and the executor for the workflow tasks.

## v1 APIs
Flow service provides APIs for:

* User & Team Management
* Creating / Updating workflows
* Executing workflows as Directed acyclic graph (DAG)

## Prerequisites**

1. Java 11
2. Spring Boot 2.3
3. Maven

### CloudEvents

This service listen and consumes CloudEvents.

### Quartz

The Java Quartz library is used for running scheduled jobs via mongoDB and underpins the Schedule trigger.

The following links will help provide guidance in development

- http://www.quartz-scheduler.org/documentation/quartz-2.2.2/tutorials/tutorial-lesson-04.html
- http://www.quartz-scheduler.org/documentation/2.4.0-SNAPSHOT/cookbook/UpdateTrigger.html
- https://github.com/StackAbuse/spring-boot-quartz/blob/master/src/main/java/com/stackabuse/service/SchedulerJobService.java
- https://stackabuse.com/guide-to-quartz-with-spring-boot-job-scheduling-and-automation/

## Packaging

When ready to package the service(s) as a container, the tags will trigger the appropriate GitHub Action using the
format `<svc>/<semver>`:

- Engine Service: tag = `engine/1.0.0-beta.111`
- Flow Service: tag = `flow/4.0.1`

## Developing and Testing Locally

### Pre-requisits

This service connects to MongoDB and requires Task Templates and indexes loaded through the Flow Loader. You can run
these locally, or alternatively connect to a remote MongoDB service.

### Run Local MongoDB w Docker

```
docker run --name local-mongo -d mongo:latest
```

### Load Boomerang Flow Data

```
docker run -e JAVA_OPTS="-Dspring.data.mongodb.uri=mongodb://localhost:27017/boomerang -Dflow.mongo.collection.prefix=flow -Dspring.profiles.active=flow" --network host --platform linux/amd64 boomerangio/flow-loader:latest
```

### Development via Spring

1. Clone the repository
2. Run `mvn clean install` to build the project
3. Run `mvn spring-boot:run` to start the application

### Development via Docker

```
docker buildx build --platform=linux/amd64 -f ./service-engine/Dockerfile -t flow-engine:latest .
docker run -e JAVA_OPTS="-Dspring.data.mongodb.uri=mongodb://localhost:27017/boomerang -Dflow.mongo.collection.prefix=flow -Dspring.profiles.active=flow" --platform=linux/amd64 flow-engine:latest
```

## Design Details

### Parameters and Results

The implementation is based on Tekton Params and Results.

There is limited support
for [Tekton Propagated Object Parameters](https://tekton.dev/docs/pipelines/taskruns/#propagated-object-parameters) in
that Tekton requires you to provide the Spec for the JSON Object if you are going to reference child elements. We do not
have this constraint, we essentially take the path from what is provided after `params.<param-name>`.

### Locks

For distributed locking, we use this [distributed lock](https://github.com/alturkovic/distributed-lock) project with the
Mongo implementation.

The implementation in `LockManagerImpl.java` relies on the TTL Index for Retries having been added via
the `flow.loader`.

## Error Handling

The following provides design and reference information about the status codes and error messages.

### Response Format

The format can be seen in `io.boomerang.error.ErrorDetail.java`

| Field     | Description                                                                                                          |
|-----------|----------------------------------------------------------------------------------------------------------------------|
| timestamp | UTC timestamp of when the error occurred                                                                             | 
| code      | unique identifier (int) that can be read and understood that detect and handle errors programmatically.              |
| reason    | unique identifier (string) that can be used to quickly identify and search for more information regarding the error. |
| message   | a description of the error intended for a human and an end user to provide context.                                  |
| status    | HTTP Status Code & Message                                                                                           |
| cause     | Optional present if `flow.error.include-cause=true` config property is provided                                      |

```json
{
  "timestamp": "2023-01-31T00:15:12.672+00:00",
  "code": 1001
  "reason": "QUERY_INVALID_FILTERS",
  "message": "Invalid query filters(status) have been provided.",
  "status": "400 BAD_REQUEST",
  "cause": null
}
```

### Implementation

The implementation allows for known and custom exceptions in the code.

Known codes are indexed in the `io.boomerang.error.BoomerangError.java` with the message text in `messages.properties`.
Alternatively, a custom exception can be thrown in the code however this will lose the benefit of localization (
_future_)

## Feature Flags

### Security

Security is enabled / disabled through the `flow.authorization.enabled` flag in the application.properties

The following classes are conditionally loaded based on this flag

| Class                                                      | Condition |
|------------------------------------------------------------|-----------|
| AuthenticationFilter                                       | true      |
| InterceptorConfig (and by association SecurityInterceptor) | true      |
| SecurityConfiguration                                      | true      |
| SecurityDisabledConfiguration                              | false     |

## Community and contributing

- Bugs and features: [open an issue](https://github.com/boomerang-io/flow/issues/new/choose) — see [CONTRIBUTING.md](CONTRIBUTING.md).
- Questions: the [Boomerang Slack](https://join.slack.com/t/boomerang-io/shared_invite/zt-pxo2yw2o-c3~6YvWkKNrKIwhIBAKhaw) — see [SUPPORT.md](SUPPORT.md).
- Security: [SECURITY.md](SECURITY.md). Maintainers: [MAINTAINERS.md](MAINTAINERS.md). Roadmap: [ROADMAP.md](ROADMAP.md).

The former central tracker, [`boomerang-io/community`](https://github.com/boomerang-io/community), is archived; its
open issues were triaged into this repository on 2026-09-01 and its old URLs redirect.
