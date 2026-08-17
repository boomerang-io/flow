//@ts-nocheck
import { summaries, revisions } from "ApiServer/fixtures";
import { vi } from "vitest";
import { EditorContextProvider } from "State/context";

const revisionDispatch = vi.mock();

export function EditorContextRender({ children, value }) {
  return (
    <EditorContextProvider
      value={{
        revisionDispatch,
        revisionState: revisions[0],
        summaryData: summaries[0],
        tasksData: [],
        ...value,
      }}
    >
      {children}
    </EditorContextProvider>
  );
}
