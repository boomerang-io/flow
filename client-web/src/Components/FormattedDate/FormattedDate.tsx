import { useCallback } from "react";
import moment from "moment";
import { useHydrated } from "Components/ClientOnly";

type FormatDate = (value: moment.MomentInput, format: string) => string;

/**
 * Formats a date in the viewer's timezone without breaking hydration. The server render and the
 * browser render that hydrates it both format in UTC, so they produce the same text whatever
 * timezone the server and the viewer are in; the viewer's own timezone swaps in on the next
 * render. A plain `moment(value).format(...)` during render formats in the server's timezone on
 * the server and the viewer's in the browser, and React then throws the server render away.
 */
export function useFormatDate(): FormatDate {
  const isHydrated = useHydrated();
  return useCallback(
    (value: moment.MomentInput, format: string) => (isHydrated ? moment(value) : moment.utc(value)).format(format),
    [isHydrated],
  );
}

/** `useFormatDate` as an element, for JSX and class components. */
export default function FormattedDate({ value, format }: { value: moment.MomentInput; format: string }) {
  const formatDate = useFormatDate();
  return <>{formatDate(value, format)}</>;
}
