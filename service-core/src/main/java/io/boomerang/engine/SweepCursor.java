package io.boomerang.engine;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Function;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Where a sweep's next page starts. A sweep that pages its candidates oldest first reads the same
 * rows every time while they stay candidates, so a row behind a full page of rows the sweep leaves
 * alone is never reached. The cursor starts each page after the last row the previous page read,
 * by the sort field and then the id, and when the rows after it run short it continues from the
 * oldest in the same page. With fewer candidates than a page every one is read every time, as
 * before; with more, every one is read within a few sweeps. Kept per instance: each sweep it serves
 * is idempotent, so instances reading the same rows is harmless.
 */
public final class SweepCursor {

  private final String field;
  private Date lastValue;
  private Object lastId;

  public SweepCursor(String field) {
    this.field = field;
  }

  /**
   * Read the next page of candidates through {@code find}, which runs the query it is given with
   * the caller's projection, and move the cursor to the page's last row.
   */
  public synchronized <T> List<T> read(
      Criteria candidates,
      int limit,
      Function<Query, List<T>> find,
      Function<T, Date> value,
      Function<T, String> id) {
    List<T> page = new ArrayList<>();
    if (lastValue != null) {
      page.addAll(find.apply(page(new Criteria().andOperator(candidates, after()), limit)));
    }
    if (page.size() < limit) {
      Criteria fromOldest =
          (lastValue != null)
              ? new Criteria().andOperator(candidates, new Criteria().norOperator(after()))
              : candidates;
      page.addAll(find.apply(page(fromOldest, limit - page.size())));
    }
    if (page.isEmpty()) {
      reset();
    } else {
      T last = page.get(page.size() - 1);
      lastValue = value.apply(last);
      lastId = asId(id.apply(last));
    }
    return page;
  }

  /** Start the next page from the oldest candidate. */
  public synchronized void reset() {
    lastValue = null;
    lastId = null;
  }

  private Criteria after() {
    return new Criteria()
        .orOperator(
            Criteria.where(field).gt(lastValue),
            Criteria.where(field).is(lastValue).and("_id").gt(lastId));
  }

  // Sorted by the field alone, so an index on it serves the order; ties are broken by the id
  // filter above.
  private Query page(Criteria criteria, int limit) {
    return Query.query(criteria)
        .with(Sort.by(Sort.Direction.ASC, field))
        .limit(limit)
        .maxTimeMsec(5000);
  }

  private static Object asId(String id) {
    return (id != null && ObjectId.isValid(id)) ? new ObjectId(id) : id;
  }
}
