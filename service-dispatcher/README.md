# Boomerang Dispatcher Service

This service acts as a mechanism to execute tasks in a secure and highly performant way and connects to the Engine
service to register and request a queue using a long poll mechanism.

It executes template, script, custom and ai tasks either in a Kubernetes cluster, using the
[Fabric8 Kubernetes Java Client](https://github.com/fabric8io/kubernetes-client), or on a single Docker host,
using [docker-java](https://github.com/docker-java/docker-java). The per-task runtime sits behind the
`io.boomerang.executor.TaskExecutor` SPI, selected at startup by `dispatcher.executor`:

| `dispatcher.executor` | Implementation | Runtime object | Notes |
| ---------------- | -------------- | -------------- | ----- |
| `tekton` (default) | `io.boomerang.kube.TektonServiceImpl` | Tekton `TaskRun` (v1) | Results via Tekton results; needs Tekton Pipelines installed. |
| `kube-jobs` | `io.boomerang.kube.KubeJobsExecutor` | `batch/v1` `Job` | No Tekton dependency. `kube.task.backOffLimit` / `restartPolicy` / `ttlDays` apply; the task timeout becomes `activeDeadlineSeconds`. Results are read from the `task` container's termination message (`RESULTS_PATH=/dev/termination-log`, JSON object or Tekton `[{key,value}]` array, 4096-byte Kubernetes cap). Scripts are mounted at `/scripts/script` and MUST start with a shebang. |
| `docker` | `io.boomerang.docker.DockerExecutor` | One container on the host daemon | No Kubernetes at all — the quickstart runtime. Reaches the daemon via `dispatcher.docker.host`, then `DOCKER_HOST`, then the local socket. The executor enforces the task timeout itself (Docker has no deadline) and reports `DeadlineExceeded`. Results are read from `RESULTS_PATH=/results.json`, copied out of the exited container. Scripts land at `/scripts/script` and MUST start with a shebang. Workspaces are labelled named volumes. No runtime class, node selector, tolerations, host aliases or image pull secret; sizing comes from the same `kube.resource.limit.*` as the Kubernetes executors. |

## Task types and the `ai` type

`flow.dispatcher.task-types` is the list this deployment registers with the engine at startup; the
engine hands it claims for those types only. It defaults to `template,custom,script,ai`, so a
single-dispatcher install runs AI tasks out of the box. `generic` is dispatchable but not registered
by default — add it explicitly to a deployment that should run it:

| Type | Image and command | Register with |
| ---- | ----------------- | ------------- |
| `template`, `custom`, `script` | Authored on the task or workflow node | Default |
| `generic` | Authored | `flow.dispatcher.task-types=template,custom,script,generic` |
| `ai` | Resolved by the dispatcher: `flow.dispatcher.ai.image` (`boomerangio/task-ai`) and the command `prompt` | Default |

An `ai` task's author never builds a container and never names an image. `TaskImageResolver`
(`io.boomerang.executor.TaskImageResolver`) supplies the image and command for type `ai` and ignores
any image, command or script that reached the spec, so a definition cannot point the AI worker at a
different container. Both executors ask the resolver instead of reading `spec.image` directly,
so the behaviour is identical on Tekton and on Kubernetes Jobs. Params and results are unchanged:
`PARAM_<NAME>` in, `RESULTS_PATH` out.

**Where the image comes from.** The worker is an ordinary task image, not a product image: it is
built and released from the [`boomerang-io/tasks`](https://github.com/boomerang-io/tasks) repository
(`tasks/ai`) and published as `boomerangio/task-ai`, on its own version line from
`task-ai@<version>` tags. The product tag (`5.x.y`) does not build it, so the two move
independently — but the param and result contract is shared between that image and the seeded `ai`
catalogue task in this repository, so a change to either side has to land on both. The contract is
documented in that repository's `tasks/ai/README.md`.

`flow.dispatcher.ai.image` defaults to an exact version, `boomerangio/task-ai:1.0.0`, so a deployment
runs a reproducible worker. Move it deliberately:

```properties
flow.dispatcher.ai.image=boomerangio/task-ai:1.2.3
```

**An AI network zone.** A dispatcher deployment registered with `flow.dispatcher.task-types=ai`
receives `ai` claims and nothing else, so every pod it creates is the AI worker image talking to the
configured endpoint. Run it in a namespace whose egress policy allows that endpoint and nothing
else, and set the general deployment to `template,custom,script` (dropping `ai`): no other task type
can then reach the AI network path, and no AI task can run outside it. This is the same shape as the
isolation tier (decision 0042) — one property per deployment, a second deployment for a second
zone — and needs no per-task field or routing rule, because the engine already routes claims by
registered task type.

## Container resources

Every task container is sized by six deployment-wide properties, read in one place
(`io.boomerang.executor.TaskResourceResolver`) and applied by all three executors — on the `task` container for
`kube-jobs`, on the step's `computeResources` for `tekton`, and on the container's host config for `docker`.
There is one property set for every runtime:

| Property | Default | Applied as |
| -------- | ------- | ---------- |
| `kube.resource.request.memory` | `2Gi` | `requests.memory` |
| `kube.resource.limit.memory` | `16Gi` | `limits.memory` |
| `kube.resource.request.ephemeral-storage` | `2Gi` | `requests.ephemeral-storage` |
| `kube.resource.limit.ephemeral-storage` | `16Gi` | `limits.ephemeral-storage` |
| `kube.resource.request.cpu` | *(empty)* | `requests.cpu` |
| `kube.resource.limit.cpu` | *(empty)* | `limits.cpu` |

Each value is a Kubernetes quantity (`16Gi`, `500m`) and each may be blank. Blank sets that request or limit not
at all — never an empty quantity, never a zero limit — so a deployment can run with memory limits and no CPU
limit, or with nothing at all, in which case the container carries no resources block. CPU ships blank on purpose:
a CPU limit throttles a task rather than failing it, so an operator opts in.

Sizing is per deployment, not per task — the same shape as the isolation tier (decision 0042). A workload that
needs a bigger container runs a second dispatcher deployment with its own task types.

A memory-backed `/data` (`kube.task.storage.data.memory` plus the task's `worker.storage.data.memory` param) is a
tmpfs: what the task writes there counts against the **memory** limit, not against ephemeral-storage. That is how
a container that would breach the ephemeral-storage limit keeps running, so a deployment that enables it sizes
memory to cover the data as well.

The `docker` executor reads the same values through the same resolver, as the counts Docker takes rather than
quantity strings: `memoryLimitBytes()` becomes the container's memory limit and `cpuLimitNanos()` its nano-CPUs,
so `16Gi` and `500m` mean on Docker exactly what they mean on Kubernetes. Blank means the same thing there too —
Docker imposes no limit. Docker has no ephemeral-storage concept, so that pair is ignored by the `docker`
executor and applies to the two Kubernetes executors only; requests have no meaning on a single host either,
because there is no scheduler to inform.

`dispatcher.tasks.runtimeClassName` sets the Pod `runtimeClassName` (gVisor / Kata / Confidential Containers) for every
task on BOTH Kubernetes executors — the Jobs executor puts it on the pod spec, the Tekton executor on the TaskRun
`podTemplate`. One setting per agent deployment; run a second agent deployment for a different isolation tier.
The Docker executor has no equivalent.

The Kubernetes executors build the same volumes: `/data` (emptyDir) and the `workflow` / `workflowrun` workspace PVCs at
`/workspace/<type>` (or the declared mount path). The Docker executor binds a named volume at the same paths and needs
no `/data` mount — a container's writable layer already is that scratch space. Which storage backs a workspace sits
behind `io.boomerang.dispatcher.WorkspaceStore`, so one reconcile path releases a claim or a volume. Params reach the task container two ways, both usable by any image,
Flow-aware or not:

- `$(params.<name>)` references in `script`, `command`, `arguments` and `envs` are substituted by the engine before
  dispatch.
- `PARAM_<NAME>` environment variables, one per param (the name upper-cased, any character outside `[A-Za-z0-9_]`
  replaced with `_`; non-string values are JSON-encoded). `PARAM_NAMES` lists the original names, comma-separated, so
  a task library can map `PARAM_PRIVATEKEY` back to `privateKey`. Two params whose sanitised names collide fail the
  Task rather than silently overwriting each other.

There is no file channel for params; structured or large inputs belong on a workspace mount, with the param carrying
the path or URI. Explicitly declared task env vars win on name collision with a generated var. Every executor also sets
`RESULTS_PATH`: a directory for Tekton (`/tekton/results`, one file per result), a single file for Jobs
(`/dev/termination-log`, one JSON object) and for Docker (`/results.json`, one JSON object).

When writing new integrations, it is recommended to look through the Kubernetes Client Docs to find the exact Client
method to use and then look at the API code to see how it works for advance configurations such as the Watcher API.

## Development

`dispatcher.executor=docker` needs only a reachable Docker daemon; `docker-compose.docker.yml` at the repository
root layers this service onto the local stack with `/var/run/docker.sock` mounted. That mount is root-equivalent
control of the host daemon — fine for a laptop, not a deployment posture.

When running the service against Kubernetes you need access to a kubernetes API endpoint. This service is set up to use whatever
the kubeconfig is pointing to. If that kubeconfig context has no namespace set, startup fails fast with an
`IllegalStateException` rather than the first task job failing — set `kube.namespace` to pin the namespace explicitly.

## RBAC

The controller needs to run with special Kubernetes RBAC. Please see the helm
charts [rbac-role-controller.yaml](https://github.com/boomerang-io/charts/blob/main/bmrg-flow/templates/rbac-role-controller.yaml)
to see more about whats needed.

### Verification

`kubectl auth can-i create taskruns --as=system:serviceaccount:bmrg-dev:bmrg-flow-controller`

## References

### Fabric8 Kubernetes Java Client

- [Client](https://github.com/fabric8io/kubernetes-client)
- [Tekton extension](https://github.com/fabric8io/kubernetes-client/tree/master/extensions/tekton)
- [Cheatsheet](https://github.com/fabric8io/kubernetes-client/blob/master/doc/CHEATSHEET.md)
- [Che example code](https://www.programcreek.com/java-api-examples/?code=eclipse%2Fche%2Fche-master%2Finfrastructures%2Fkubernetes%2Fsrc%2Fmain%2Fjava%2Forg%2Feclipse%2Fche%2Fworkspace%2Finfrastructure%2Fkubernetes%2Fnamespace%2FKubernetesPersistentVolumeClaims.java#)
- [Access Tekton Pipelines in Java using Fabric8 Tekton Client](https://itnext.io/access-tekton-pipelines-in-java-using-fabric8-tekton-client-bd727bd5806a)
- [Difference between Fabric8 and Official Kubernetes Java Client](https://itnext.io/difference-between-fabric8-and-official-kubernetes-java-client-3e0a994fd4af)

### Kubernetes ConfigMap

We currently use projected volumes however subpath was considered.

- Projected
  Volumes: https://unofficial-kubernetes.readthedocs.io/en/latest/tasks/configure-pod-container/projected-volume/
- Projected Volumes: https://docs.okd.io/latest/dev_guide/projected_volumes.html
- Projected
  Volumes: https://stackoverflow.com/questions/49287078/how-to-merge-two-configmaps-using-volume-mount-in-kubernetes
- SubPath: https://blog.sebastian-daschner.com/entries/multiple-kubernetes-volumes-directory

### [Deprecated] Kubernetes Java Client

- Client: https://github.com/kubernetes-client/java
- Examples: https://github.com/kubernetes-client/java/blob/master/examples/src/main/java/io/kubernetes/client/examples
- API: https://github.com/kubernetes-client/java/tree/master/kubernetes/src/main/java/io/kubernetes/client/apis
- API Object Docs: https://github.com/kubernetes-client/java/tree/master/kubernetes/docs

## Stash

### Container States

When monitoring the Job/Pod/Container there are additional error states in the waiting status that need to be accounted
for

- https://stackoverflow.com/questions/57821723/list-of-all-reasons-for-container-states-in-kubernetes
- https://github.com/kubernetes/kubernetes/blob/d24fe8a801748953a5c34fd34faa8005c6ad1770/pkg/kubelet/images/types.go

### Lifecycle Container Hooks

The following code was written to interface with the container lifecycle hooks of postStart and preStop however there
were two main issues:

1. no guarantee that postStart would execute before the main container code -> we went with an initContainer
2. preStop was not executing on jobs when the pod didn't get sent a SIG as it completed successfully so was technically
   never terminated.

```
V1Lifecycle lifecycle = new V1Lifecycle();
V1Handler postStartHandler = new V1Handler();
V1ExecAction postStartExec = new V1ExecAction();
postStartExec.addCommandItem("/bin/sh");
postStartExec.addCommandItem("-c");
postStartExec.addCommandItem("touch /lifecycle/lock");
postStartHandler.setExec(postStartExec);
lifecycle.setPostStart(postStartHandler);
V1Handler preStopHandler = new V1Handler();
V1ExecAction preStopExec = new V1ExecAction();
preStopExec.addCommandItem("/bin/sh");
preStopExec.addCommandItem("-c");
preStopExec.addCommandItem("rm -f /lifecycle/lock");
preStopHandler.setExec(preStopExec);
lifecycle.setPreStop(preStopHandler);
container.lifecycle(lifecycle);
```

- PreStop Hooks arent called on Successful Job: https://github.com/kubernetes/kubernetes/issues/55807
- https://kubernetes.io/docs/concepts/workloads/pods/pod/#termination-of-pods
- https://v1-13.docs.kubernetes.io/docs/concepts/containers/container-lifecycle-hooks/
- https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/
- https://www.alibabacloud.com/blog/pod-lifecycle-container-lifecycle-hooks-and-restartpolicy_594727
- https://www.magalix.com/blog/kubernetes-patterns-application-process-management-1

### Sidecars

- Sidecar Containers in Jobs: https://github.com/kubernetes/kubernetes/issues/25908
- Sidecar Containers in Jobs 2: https://stackoverflow.com/questions/36208211/sidecar-containers-in-kubernetes-jobs
- Terminating a sidecar
  container: https://medium.com/@cotton_ori/how-to-terminate-a-side-car-container-in-kubernetes-job-2468f435ca99
- Sidecar Container Design Patterns: https://www.weave.works/blog/container-design-patterns-for-kubernetes/
- KEP (Kubernetes Enhancement Proposal for
  Sidecars: https://github.com/kubernetes/enhancements/blob/master/keps/sig-apps/sidecarcontainers.md#upgrade--downgrade-strategy
- https://blog.bryantluk.com/post/2018/05/13/terminating-sidecar-containers-in-kubernetes-job-specs/

### Output Properties

- Argo Variables: https://github.com/argoproj/argo/blob/master/docs/variables.md
- Argo Output Parameters: https://github.com/argoproj/argo/blob/master/examples/README.md#output-parameters
- Container Namespace Sharing: google it

### Process Namespace Sharing

- https://github.com/kubernetes/kubernetes/issues/1615
- https://github.com/kubernetes/enhancements/issues/495
- https://kubernetes.io/docs/tasks/configure-pod-container/share-process-namespace/
- https://hackernoon.com/the-curious-case-of-pid-namespaces-1ce86b6bc900
- https://www.mirantis.com/blog/multi-container-pods-and-container-communication-in-kubernetes/

