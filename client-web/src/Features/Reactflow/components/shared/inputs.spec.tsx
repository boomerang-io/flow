import { DynamicFormik } from "@boomerang-io/carbon-addons-boomerang-react";
import { render, screen } from "@testing-library/react";
import { textEditorProps } from "./inputs";

// Stands in for the text editor modal, which loads CodeMirror: it shows the language it was handed.
function LanguageProbe({ language }: { language?: string }) {
  return <p data-testid="editor-language">{language ?? "none"}</p>;
}

function renderEditorInput(type: string) {
  // The add-on's input type wants every governing field; a text editor input sets none of them.
  const inputs = [{ key: "script", label: "Shell Script", type }] as unknown as React.ComponentProps<
    typeof DynamicFormik
  >["inputs"];
  render(
    <DynamicFormik
      initialValues={{}}
      inputs={inputs}
      dataDrivenInputProps={{ TextEditor: LanguageProbe }}
      textEditorProps={textEditorProps([], {})}
    >
      {({ inputs: rendered }: { inputs: React.ReactNode }) => <div>{rendered}</div>}
    </DynamicFormik>,
  );
  return screen.getByTestId("editor-language").textContent;
}

describe("textEditorProps", () => {
  // The form's DataDrivenInput keeps an input's `type` to itself, so the editor only learns a
  // `texteditor::shell` input is shell when the form hands the language over as its own prop.
  it("opens a texteditor::shell input in the shell language", () => {
    expect(renderEditorInput("texteditor::shell")).toBe("shell");
  });

  it("names no language for a plain texteditor input", () => {
    expect(renderEditorInput("texteditor")).toBe("none");
  });
});
