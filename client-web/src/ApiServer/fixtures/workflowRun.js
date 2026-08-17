const workflowRun = {
    "id": "651e4789ab1cb56bc8976ae4",
    "creationDate": "2023-10-05T05:20:08.956+00:00",
    "status": "succeeded",
    "phase": "finalized",
    "startTime": "2023-10-05T05:20:09.648+00:00",
    "duration": 8336,
    "workflowRef": "651b91a77fbb1a64ab8b7154",
    "workflowRevisionRef": "651cffa3e99fd73f5122879d",
    "labels": {},
    "annotations": {
        "boomerang.io/task-deletion": "Never",
        "boomerang.io/task-default-image": "boomerangio/worker-flow:2.11.15",
        "boomerang.io/context-params": {
            "workflow-id": "651b91a77fbb1a64ab8b7154",
            "taskrun-id": "",
            "wfe-url": "localhost:7700/api/v2/wfe",
            "event-url": "localhost:7700/api/v2/event",
            "taskrun-name": "",
            "taskrun-type": "",
            "workflow-name": "",
            "webhook-url": "localhost:7700/api/v2/webhook",
            "workflow-version": "",
            "workflowrun-initiator": "",
            "workflowrun-id": "651e4789ab1cb56bc8976ae4",
            "workflowrun-trigger": "manual"
        },
        "boomerang.io/team-name": "Team",
        "boomerang.io/kind": "WorkflowRun",
        "boomerang.io/team-params": {
            "echo-parameter": "test"
        },
        "boomerang.io/global-params": {},
        "boomerang.io/generation": "4"
    },
    "params": [
        {
            "name": "echo-team-parameter",
            "value": "test"
        }
    ],
    "tasks": [
        {
            "id": "651e4789ab1cb56bc8976ae5",
            "type": "start",
            "name": "start",
            "status": "succeeded",
            "phase": "completed",
            "creationDate": "2023-10-05T05:20:09.138+00:00",
            "duration": 0,
            "labels": {},
            "params": [],
            "annotations": {
                "boomerang.io/position": {
                    "x": -295,
                    "y": -5
                }
            },
            "results": [],
            "workspaces": [],
            "spec": {
                "debug": false
            },
            "workflowRef": "651b91a77fbb1a64ab8b7154",
            "workflowRevisionRef": "651cffa3e99fd73f5122879d",
            "workflowRunRef": "651e4789ab1cb56bc8976ae4"
        },
        {
            "id": "651e4789ab1cb56bc8976ae6",
            "type": "end",
            "name": "end",
            "status": "succeeded",
            "phase": "completed",
            "creationDate": "2023-10-05T05:20:09.180+00:00",
            "duration": 0,
            "labels": {},
            "params": [],
            "annotations": {
                "boomerang.io/position": {
                    "x": 510,
                    "y": -7
                }
            },
            "results": [],
            "workspaces": [],
            "spec": {
                "debug": false
            },
            "workflowRef": "651b91a77fbb1a64ab8b7154",
            "workflowRevisionRef": "651cffa3e99fd73f5122879d",
            "workflowRunRef": "651e4789ab1cb56bc8976ae4"
        },
        {
            "id": "651e4789ab1cb56bc8976ae7",
            "type": "script",
            "name": "Execute Shell",
            "status": "succeeded",
            "phase": "completed",
            "creationDate": "2023-10-05T05:20:09.187+00:00",
            "startTime": "2023-10-05T05:20:09.985+00:00",
            "duration": 7924,
            "statusMessage": "Task (651e4789ab1cb56bc8976ae7) has been executed successfully.",
            "labels": {},
            "params": [
                {
                    "name": "path",
                    "value": "/"
                },
                {
                    "name": "shell",
                    "value": ""
                },
                {
                    "name": "script",
                    "value": "echo \"test\"\n\necho \"test\""
                }
            ],
            "annotations": {
                "boomerang.io/position": {
                    "x": 38,
                    "y": -6
                },
                "boomerang.io/team-name": "Team",
                "boomerang.io/kind": "TaskRun",
                "boomerang.io/generation": "4"
            },
            "results": [],
            "workspaces": [],
            "spec": {
                "arguments": [
                    "shell",
                    "execute"
                ],
                "command": [],
                "image": "boomerangio/worker-flow:2.11.15",
                "debug": false,
                "deletion": "Never"
            },
            "taskRef": "execute-shell",
            "taskRef": 2,
            "workflowRef": "651b91a77fbb1a64ab8b7154",
            "workflowRevisionRef": "651cffa3e99fd73f5122879d",
            "workflowRunRef": "651e4789ab1cb56bc8976ae4"
        }
    ],
    "trigger": "manual",
    "results": [],
    "workspaces": [],
    "workflowName": "Parameter Resolution Check",
    "awaitingApproval": false
};

export default workflowRun;