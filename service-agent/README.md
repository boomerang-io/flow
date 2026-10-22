# Boomerang Agent Service for Tekton

This service acts as a mechanism to execute tasks in a secure and highly performant way and connects to the Engine
service to register and request a queue using a long poll mechanism.

This specific agent is the Tekton agents, executing template, script, and custom tasks in a Kubernetes cluster using
the [Fabric8 Kubernetes Java Client](https://github.com/fabric8io/kubernetes-client) to interact with Kubernetes
along with the Tekton extension to interact with the Tekton TaskRuns. When writing new integrations, it is
recommended to look through the Kubernetes Client Docs to find the exact Client method to use and then look at the API
code to see how it works for advance configurations such as the Watcher API.

## Development

When running the service locally you need access to a kubernetes API endpoint. This service is set up to use whatever
the kubeconfig is pointing to.

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

