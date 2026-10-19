package io.boomerang.common.util;

import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.EnumUtils;
import org.springframework.data.mongodb.core.query.Criteria;

/**
 * The filters of a list query, ANDed. Every list route builds its MongoDB criteria here, so a
 * label, a date range, an id list and an enum filter mean the same thing on every route, and a
 * malformed filter is a {@code 400 QUERY_INVALID_FILTERS} rather than a server error.
 */
public final class QueryCriteria {

  private final List<Criteria> criteria = new ArrayList<>();

  /** Match {@code field} within {@code [from, to)}; an absent bound leaves that side open. */
  public QueryCriteria between(String field, Optional<Date> from, Optional<Date> to) {
    if (from.isPresent() || to.isPresent()) {
      Criteria range = Criteria.where(field);
      from.ifPresent(range::gte);
      to.ifPresent(range::lt);
      criteria.add(range);
    }
    return this;
  }

  /**
   * Match every label: each entry is a URL-encoded {@code key=value}, split once on the first
   * {@code =} so a value may contain {@code =}. A dot in the key is matched in its stored form
   * ({@code MongoConfiguration} replaces it with {@code #}). An entry without a key or a value is
   * refused.
   */
  public QueryCriteria labels(Optional<List<String>> labels) {
    labels.ifPresent(entries -> entries.forEach(entry -> criteria.add(label(entry))));
    return this;
  }

  private static Criteria label(String entry) {
    String[] label;
    try {
      label = URLDecoder.decode(entry, StandardCharsets.UTF_8).split("=", 2);
    } catch (IllegalArgumentException e) {
      throw new BoomerangException(e, BoomerangError.QUERY_INVALID_FILTERS, "labels");
    }
    if (label.length < 2 || label[0].isBlank() || label[1].isEmpty()) {
      throw new BoomerangException(BoomerangError.QUERY_INVALID_FILTERS, "labels");
    }
    return Criteria.where("labels." + label[0].replace(".", "#")).is(label[1]);
  }

  /** Match {@code field} against any of {@code values}. */
  public QueryCriteria in(String field, Collection<?> values) {
    criteria.add(Criteria.where(field).in(values));
    return this;
  }

  /** Match {@code field} against any of {@code values} when they are given. */
  public QueryCriteria in(String field, Optional<? extends Collection<?>> values) {
    values.ifPresent(list -> in(field, list));
    return this;
  }

  /**
   * Match {@code field} against any of {@code values}, each read as a constant of {@code type}
   * ignoring case; a value that names no constant is refused, naming {@code filter}.
   */
  public <E extends Enum<E>> QueryCriteria in(
      String field, Class<E> type, Optional<List<String>> values, String filter) {
    values.ifPresent(
        list ->
            in(
                field,
                list.stream()
                    .map(
                        value -> {
                          E constant = EnumUtils.getEnumIgnoreCase(type, value);
                          if (constant == null) {
                            throw new BoomerangException(
                                BoomerangError.QUERY_INVALID_FILTERS, filter);
                          }
                          return constant;
                        })
                    .toList()));
    return this;
  }

  public QueryCriteria add(Criteria criterion) {
    criteria.add(criterion);
    return this;
  }

  /** The filters ANDed; no filter matches everything (never an empty {@code $and}). */
  public Criteria build() {
    return criteria.isEmpty()
        ? new Criteria()
        : new Criteria().andOperator(criteria.toArray(new Criteria[0]));
  }
}
