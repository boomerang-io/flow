const workflowsCompose = [
  {
    id: "5e877d1f4bbc6e0001c51e12",
    name: "Test Google Sheets as JWT",
    status: "active",
    version: 7,
    creationDate: "2022-04-13T01:41:17.701+00:00",
    annotations: { "boomerang.io/generation": "3", "boomerang.io/kind": "Workflow" },
    changelog: { author: "61d38d133aa9034ded32cae6", reason: "", date: "2022-04-13T02:26:26.467+00:00" },
    icon: "bot",
    upgradesAvailable: false,
    markdown: "# Hello world",
    triggers: {
      manual: {
        type: "manual",
        enabled: true,
        conditions: [],
      },
      webhook: {
        type: "webhook",
        enabled: false,
        conditions: [],
      },
      schedule: {
        type: "scheduler",
        enabled: false,
        conditions: [],
      },
      event: {
        type: "event",
        enabled: true,
        conditions: [],
      },
      github: {
        type: "github",
        enabled: false,
        conditions: [],
      },
    },
    config: [],
    tokens: [{ token: "268CD9268194A7B58888DC4B8FB4E6BF1358D01CEBB97F8670C544B4F076DD63", label: "default" }],
    nodes: [
      {
        id: "64cafb1706ca9f65f338ae3b",
        data: { name: "start", params: [], taskRef: null, taskVersion: null, upgradesAvailable: false },
        type: "start",
      },
      {
        id: "64cafb1706ca9f65f338ae3c",
        data: { name: "end", params: [], taskRef: null, taskVersion: null, upgradesAvailable: false },
        type: "end",
      },
      {
        id: "64cafb1706ca9f65f338ae3e",
        data: {
          name: "Create Spreadsheet 1",
          params: [
            {
              name: "privateKey",
              value:
                "-----BEGIN PRIVATE KEY-----\\nMIIEugIBADANBgkqhkiG9w0BAQEFAASCBKQwggSgAgEAAoIBAQCoUAvizrUVWGS6\\nYLy6s5NUbSjirTxPsHsphxCFuNoLwW7RVRTCngUYxbsk4b0BhGrchXm9124fuVK2\\ng8tEVfTaotMDYuPZJ7/OvBGjk1VkHwfnftcm0GQ/4mJD9MmjIi72KtkCi9H0YFcm\\nbRHsbI16nPSjm0+v8by6ZHVJTMDls+pHxk1L7P/3v85ZLjddUnJfPI3HgV12R9OO\\n6Ca+TGN/q7Gnwp2oHh6bHUOgSUlKg9UnsTziL4q7rHy/uZR+g/gZ7Q4/FhH3qtUY\\n757QQm93Ad++/zouwnqVDt90SQJis1pscQqk2GBFpTfGhV7cbaXq2w7QfTlQPPMf\\nCMTeF9JJAgMBAAECgf915FA8zrTQ0EGBbb5fnlmtAFpmvCf/+babRsNqCe1HSaRQ\\neVbK+sEFmQHR05dxpcwa0pBaRbN4oy4uRNbphB57D//+0qnoy105B7mFYVg3PFfZ\\njTXlLDBrTajwGLLdWgAhchPuKBWXV7Gfb+lzW0pUarT6eoiw16v/g1j+DbrdMuXY\\ns5azGbbrKVX0K6AHTC5Yenzl+q2s67WcGs0ufjjhmi+6cPRkP29Ab+sv6bLuFsol\\noikGtYgKO6emRZzPmaSkE4+iNPOi5nPkaVsKgbOYKhmr4BRj69IhlUhUGHdX7ArN\\n+6APWTbx8gC8lLlNMWgqkDfCvLJG2PzWYWQn4D0CgYEA5Cz6mDOXef9GzV5q66jk\\n5BpQJZ495tA8LwzrXWmYdmxauvL+ljwNcdu7O2rLeGs8xBIUL4iPXAxFQ1wGorRa\\nSwrO88Ihn/po+At44KI8DRlUBonybaPMbm6vNtybQ0dgohxuuAlw0KdkHCl72r21\\nXfclvvFfiG7oR8GApsUXI58CgYEAvNZO0sFaugoInGcL87elRX3mfGwd6aKAsY0W\\nF90WFRqCXASvy2QT8j301pvocAPjR0dXeD0z8oyxFixnjEd3NqN2nDtVay/UMrNv\\nToL8VpOP09fIX+KNKZu0j1QfYpPXKo1Vt6FSFwXwB3bcQ9/3H2iRPW/oC/qk22dk\\nS6IgARcCgYBk/Bcod2TqBHYEfG4bwFMVNSQVkhRZa/3G4RPl8s8cUlZReSv9t7RH\\nmPUjld9ZezSYnlu6sdio53FcH4V8Sw/POIhhT6DsGbqNVR55ZLPpJDWQ80qNBbp7\\ngZcgU8CPpS56cNT7tInufrcK071SETYM0U4uVMtwKY25/EUBCid0hwKBgHJ3+poZ\\nKVUi5ethL8aSKTZBxmjf5RYOMrLuUlku5MbwJdt/38yMIucyFWvOYI0eXjSEE4rI\\nlufNzz8gtbg8ThCp8Cluci4idSAy0yMlla8pjgMdh9JP88AoKcDEfq3z7ndV64L/\\n4FIigEkWDRl790Jr0bcLUf+I9UwmlY6ffYULAoGAagMWII0Il0z4CI2K9xJ+5xhY\\n20rE7V1bLa0lV48y6XQMZ/mPysfIBazv3oIZWgzV23zk6M8p162Xavr0wN3VFlC2\\nq/tj7F1aBqx1EDxPZDqcVhzdFA5Ki7/KPXJOyFEvL6p0qTUZqNpt5zeTpahGb5Zb\\nohZQq3QmUIUWMzMVvTY=\\n-----END PRIVATE KEY-----",
            },
            { name: "clientId", value: "108069983449889171270" },
            { name: "clientEmail", value: "test-28@rare-charmer-175000.iam.gserviceaccount.com" },
            { name: "title", value: "Newly Created Spreadsheet" },
            { name: "projectId", value: "rare-charmer-175000" },
            { name: "privateKeyId", value: "3dbc985055ac90c2f0fcd4db549f0b416e78ad5c" },
          ],
          taskRef: "create-spreadsheet",
          taskVersion: "1",
          upgradesAvailable: true,
        },
        type: "template",
      },
    ],
    edges: [
      {
        id: "64cafb1706ca9f65f338ae3d",
        source: "64cafb1706ca9f65f338ae3e",
        target: "64cafb1706ca9f65f338ae3c",
        type: "template",
        data: { decisionCondition: "", executionCondition: "always" },
      },
      {
        id: "64cafb1706ca9f65f338ae3f",
        source: "64cafb1706ca9f65f338ae3b",
        target: "64cafb1706ca9f65f338ae3e",
        type: "start",
        data: { decisionCondition: "", executionCondition: "always" },
      },
    ],
  },
];

export default workflowsCompose;
