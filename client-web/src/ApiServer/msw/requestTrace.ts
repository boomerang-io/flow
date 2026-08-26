import { HttpResponse, delay } from "msw";
import type { JsonBodyType } from "msw";

/*
 * A spec helper for asserting that a route loader fires its independent reads together rather
 * than as a waterfall.
 *
 * Loaders block first paint (there is no per-query pending UI left once a read moves onto the
 * router), so `await`-ing independent fetches one after another multiplies the round trips a user
 * waits through. That is invisible to an ordinary assertion - the resolved data is identical
 * either way - so it is asserted on the ORDER of the requests instead.
 *
 * Each traced resolver records `<name>:start`, waits, then records `<name>:end`. A sequential
 * loader cannot, by definition, start its second request before its first has ended, so
 * `startedTogether(n)` is false for a waterfall and true for a single wave. There is no timing
 * race: the ordering is causal, and the delay only makes it observable.
 */
export function createRequestTrace() {
  const events: string[] = [];

  return {
    events,

    /** An MSW resolver that brackets its response with start/end markers. */
    resolver(name: string, body: JsonBodyType = {}) {
      return async () => {
        events.push(`${name}:start`);
        await delay(20);
        events.push(`${name}:end`);
        return HttpResponse.json(body);
      };
    },

    /** True when the first `count` traced requests all started before any of them finished. */
    startedTogether(count: number) {
      return events.slice(0, count).every((event) => event.endsWith(":start"));
    },
  };
}
