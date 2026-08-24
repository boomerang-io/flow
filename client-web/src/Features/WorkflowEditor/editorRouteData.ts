import { useMatches } from "react-router-dom";
import type {
  CalendarEntry,
  ChangeLog,
  PaginatedSchedulesResponse,
  PaginatedTaskResponse,
  PaginatedWorkflowResponse,
  WorkflowCanvas,
} from "Types";

/*
 * Client-safe half of the workflow editor's route contract: the shape
 * `/:workspace/editor/:workflow/*`'s loader produces, plus the hook the editor's components read
 * it back with.
 *
 * Deliberately split from editorRoute.ts, which imports Config/serverFetch (Node-only:
 * `process.env`, no browser cookie jar). Only files under app/routes/ and what they re-export get
 * their loader/action code stripped from the client bundle by route-module splitting
 * (v8_splitRouteModules) - Configure.tsx and Schedule/Schedule.tsx are ordinary components
 * rendered inside Editor.tsx's descendant <Routes>, so they must import from here, never from
 * editorRoute.ts. Same split, same reason, as Components/TokenSection/tokenRouteData.ts.
 */

export interface EditorScheduleData {
  schedulesData?: PaginatedSchedulesResponse;
  calendarEntries: Array<CalendarEntry>;
  errorLoadingSchedules: boolean;
  errorLoadingCalendar: boolean;
}

export interface EditorData {
  workflow?: WorkflowCanvas;
  workflows?: PaginatedWorkflowResponse;
  changeLog?: ChangeLog;
  availableParameters?: Array<string>;
  tasks?: PaginatedTaskResponse;
  workspaceTasks?: PaginatedTaskResponse;
  /*
   * Mirrors the previous "if any of the six queries errored, render <Error />" behaviour in
   * Editor.tsx. A failed fetch resolves with this flag rather than throwing, so the router's
   * errorElement doesn't replace the whole route (see GlobalParameters.tsx for the reference).
   */
  errorLoading: boolean;
  /*
   * Its own field rather than folded into `errorLoading`: the GitHub integration failing (or
   * simply not being installed) is a normal state Configure.tsx already renders an "Integration
   * Required" notification for - it never blanked the editor before, and must not now.
   */
  githubAppInstallation: any | null;
  /*
   * null on every tab except Schedules. The loader only fetches schedules/calendar when the
   * route's splat is "schedule", which is what react-query's mount-driven fetching gave before -
   * Schedule/Schedule.tsx is rendered by Editor.tsx's <Routes> and so only mounted on that tab.
   */
  schedule: EditorScheduleData | null;
}

export interface EditorRouteData {
  editor: EditorData;
}

/*
 * Read the editor loader data off the matched route.
 *
 * useMatches() rather than useLoaderData() for the same reason TokenSection uses it (see
 * Components/TokenSection/tokenRouteData.ts): Configure and Schedule render inside the editor's
 * *descendant* <Routes>, where useLoaderData() resolves against a descendant match that has no
 * loader. useMatches() reads the data router's own state, so it answers identically from
 * Editor.tsx (a real route element) and from its descendants.
 *
 * Returns undefined when no matched route supplied the key, leaving the caller's own fallback to
 * decide what to render rather than throwing during an unrelated route's render.
 */
export function useEditorRouteData(): EditorData | undefined {
  const matches = useMatches();
  for (let index = matches.length - 1; index >= 0; index--) {
    const data = matches[index]?.data as Partial<EditorRouteData> | undefined;
    if (data && typeof data === "object" && data.editor) {
      return data.editor;
    }
  }
  return undefined;
}
