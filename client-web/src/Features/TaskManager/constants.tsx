export const TemplateRequestType = {
  Copy: "copy",
  New: "new",
  Overwrite: "overwrite",
};

export const FieldTypes = {
  boolean: "Boolean",
  email: "Email",
  number: "Number",
  password: "Password",
  select: "Select",
  string: "String",
  text: "Text",
  textarea: "Text Area",
  texteditor: "Text Editor",
  "texteditor::shell": "Text Editor - Shell",
  "texteditor::text": "Text Editor - Text",
  "texteditor::yaml": "Text Editor - YAML",
  "texteditor::javascript": "Text Editor - JavaScript/JSON",
  time: "Time",
  url: "URL",
};

export interface FormProps {
  name: string;
  description: string;
  icon: string;
  image: string;
  category: string;
  currentConfig: {};
  arguments: string;
  command: string;
  script: string;
  comments: string;
}
