const content = [{yaml:`apiVersion: tekton.dev/v1beta1
kind: Task
metadata:
  name: send-email-with-postmark-template
  labels: {}
  annotations:
    boomerang.io/displayName: Send Email with Postmark Template
    boomerang.io/version: 2
    boomerang.io/params:
    - key: token
      description: null
      label: API Token
      type: string
      minValueLength: null
      maxValueLength: null
      options: null
      required: true
      placeholder: ""
      language: null
      disabled: null
      defaultValue: null
      value: null
      values: null
      readOnly: false
      hiddenValue: null
      helperText: ""
    - key: to
      description: null
      label: To
      type: string
      minValueLength: null
      maxValueLength: null
      options: null
      required: true
      placeholder: ""
      language: null
      disabled: null
      defaultValue: null
      value: null
      values: null
      readOnly: false
      hiddenValue: null
      helperText: Supports up to 50 recipients. Comma delimited.
    - key: from
      description: null
      label: From
      type: string
      minValueLength: null
      maxValueLength: null
      options: null
      required: true
      placeholder: ""
      language: null
      disabled: null
      defaultValue: null
      value: null
      values: null
      readOnly: false
      hiddenValue: null
      helperText: Email address or with name information
    - key: templateId
      description: null
      label: Template Id
      type: string
      minValueLength: null
      maxValueLength: null
      options: null
      required: null
      placeholder: ""
      language: null
      disabled: null
      defaultValue: null
      value: null
      values: null
      readOnly: false
      hiddenValue: null
      helperText: null
    - key: templateAlias
      description: null
      label: Template Alias
      type: string
      minValueLength: null
      maxValueLength: null
      options: null
      required: null
      placeholder: ""
      language: null
      disabled: null
      defaultValue: null
      value: null
      values: null
      readOnly: false
      hiddenValue: null
      helperText: null
    - key: templateModel
      description: null
      label: Template Variables
      type: string
      minValueLength: null
      maxValueLength: null
      options: null
      required: false
      placeholder: ""
      language: null
      disabled: null
      defaultValue: null
      value: null
      values: null
      readOnly: false
      hiddenValue: null
      helperText: ""
    - key: tag
      description: null
      label: Tag
      type: string
      minValueLength: null
      maxValueLength: null
      options: null
      required: false
      placeholder: ""
      language: null
      disabled: null
      defaultValue: null
      value: null
      values: null
      readOnly: false
      hiddenValue: null
      helperText: ""
    - key: messageStream
      description: null
      label: Message Stream
      type: string
      minValueLength: null
      maxValueLength: null
      options: null
      required: false
      placeholder: ""
      language: null
      disabled: null
      defaultValue: null
      value: null
      values: null
      readOnly: false
      hiddenValue: null
      helperText: Message Stream ID that's used for sending
    boomerang.io/category: Communication
    boomerang.io/kind: TaskTemplate
    boomerang.io/icon: Message
    boomerang.io/generation: 3
    boomerang.io/verified: true
spec:
  description: Send an email from a template using a template ID and variables.
  params:
  - name: token
    type: string
    description: "If you have a Postmark account, you will be able to find or create\
      \ an API token via their Server UI"
    default: ""
  - name: to
    type: string
    description: ""
    default: ""
  - name: from
    type: string
    description: sender@example.com or "Boomerang Joe <sender@example.com>"
    default: ""
  - name: templateId
    type: string
    description: Required if TemplateAlias is not specified.
    default: ""
  - name: templateAlias
    type: string
    description: Required if TemplateID is not specified.
    default: null
  - name: templateModel
    type: string
    description: You can find sample variables in the Postmark Template UI
    default: null
  - name: tag
    type: string
    description: Categorize outgoing emails and get detailed statistics
    default: ""
  - name: messageStream
    type: string
    description: ""
    default: outbound
  steps:
  - name: send-email-with-postmark-template
    image: ""
    script: ""
    workingDir: ""
    env: []
    command: []
    args:
    - mail
    - sendPostmarkEmailWithTemplate
  timeout: null
  results:
  - description: Recipient email address
    name: To
  - description: Timestamp
    name: SubmittedAt
  - description: ID of message
    name: MessageID
  - description: See Postmark API Error Codes
    name: ErrorCode
  - description: Response message
    name: Message`}];

export default content;