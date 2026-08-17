# Boomerang Flow Web Client

The web client for Boomerang Flow, a low-code, cloud-native workflow automation tool.

Learn more about our project on the [docs site](https://useboomerang.io/docs).

View the current project work in our [community roadmap repo](https://github.com/boomerang-io/roadmap).

## Getting Started with Vite

This project is built with [Vite](https://vitejs.dev/).

## Local Development

### Run with Stubs

`npm run start`

### Run with Local Services

#### Kubernetes Setup

> Note: we recommend using Orbstack or Rancher Desktop if you want to run a simple local Kubernetes cluster.

1. `kubectl create ns workflows`
2. `kubectl apply --filename https://storage.googleapis.com/tekton-releases/pipeline/latest/release.yaml`
3. `helm repo add boomerang-io https://raw.githubusercontent.com/boomerang-io/charts/index`
4. `helm repo add bitnami-pre-2022 https://raw.githubusercontent.com/bitnami/charts/eb5f9a9513d987b519f0ecd732e7031241c50328/bitnami`
5. `helm upgrade --version 7.8.8 --install mongodb -n workflows bitnami-pre-2022/mongodb --set mongodbDatabase=boomerang --set mongodbUsername=boomerang`
6. `docker pull --platform linux/amd64  boomerangio/{flow-loader:1.0.0-beta.40,flow-service-handler:7.0.0-beta.24,flow-service-engine:1.0.0-beta.79,flow-service-workflow:4.0.0-beta.197}`
7. `helm upgrade --install workflows -n workflows -f ./flowabl-dev-5.1.0-values.yaml boomerang-io/bmrg-flow --version 5.1.7`
8. `kubectl get secret --namespace workflows mongodb -o jsonpath="{.data.mongodb-password}" | base64 --decode | pbcopy`

#### Connect to Database with Robo 3T

Connect to database using client with credentials

username: boomerang
database: boomerang
password: `kubectl get secret --namespace workflows mongodb -o jsonpath="{.data.mongodb-password}" | base64 --decode | pbcopy`

#### Setup Database

1. Create `workflows_users` collection
2. Create user

```json
{
    "email" : "tim@test.com",
    "name" : "Tim",
    "type" : "user",
    "creationDate" : ISODate("2024-01-11T03:05:41.738Z"),
    "status" : "active",
    "labels" : {},
    "settings" : {
        "isFirstVisit" : true,
        "isShowHelp" : true,
        "hasConsented" : false
    },
}
```

#### Create Tokens

1. Create global token

POST `http://<workflow-service>/internal/debug/token`

```json
{
  "type": "global",
  "name": "global-test-token",
  "permissions": ["**/**"]
}
```

2. Create user token

POST `http://<workflow-service>/api/v2/token`

HEADER Authorization: Bear <global token from above>

```json
{
  "type": "user",
  "name": "test-token-user",
  "principal": "<user-id>"
}
```

#### Port Forwarding

1. Port forward to service to avoid CORS errors
   `kubectl port-forward svc/flow-services-workflow 8081:80 -n workflows`

2. Export token

export AUTH_JWT=<user_token>

3. `npm run start:pf`
