package io.boomerang.common.util;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.support.PageableExecutionUtils;

/**
 * The paging the list queries share: a limit reads one page in the sort's order, no limit reads
 * everything in that order, and the result carries the total the page needs.
 */
public final class PagingUtil {

  private PagingUtil() {}

  /**
   * Page {@code query} by {@code limit} and {@code page} in {@code sort}'s order, or with no limit
   * only sort it. Returns the {@link Pageable} the result page reports.
   */
  public static Pageable apply(
      Query query, Optional<Integer> limit, Optional<Integer> page, Sort sort) {
    if (limit.isEmpty()) {
      query.with(sort);
      return Pageable.unpaged();
    }
    Pageable pageable = PageRequest.of(page.orElse(0), limit.get(), sort);
    query.with(pageable);
    return pageable;
  }

  /** The page of {@code content} that {@code query} read, counting the total only when needed. */
  public static <T> Page<T> page(
      List<T> content,
      Pageable pageable,
      MongoTemplate mongoTemplate,
      Query query,
      Class<?> entityType) {
    return PageableExecutionUtils.getPage(
        content,
        pageable,
        () -> mongoTemplate.count(Query.of(query).skip(-1).limit(-1), entityType));
  }
}
