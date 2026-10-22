package io.boomerang.flow.sdk;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Optional;
import java.util.function.IntFunction;

/**
 * One page of a list route's answer: the rows, the count of every match, and the page that
 * follows. A query without a {@code limit} returns every match as one page.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class Page<T> {

  @JsonProperty private final List<T> content;
  @JsonProperty private final long totalElements;
  @JsonProperty private final int totalPages;
  @JsonProperty private final int number;
  @JsonProperty private final int size;
  @JsonProperty private final int numberOfElements;
  @JsonProperty private final boolean first;
  @JsonProperty private final boolean last;
  @JsonProperty private final boolean empty;

  private IntFunction<Page<T>> following;

  @JsonCreator
  Page(
      @JsonProperty("content") List<T> content,
      @JsonProperty("totalElements") long totalElements,
      @JsonProperty("totalPages") int totalPages,
      @JsonProperty("number") int number,
      @JsonProperty("size") int size,
      @JsonProperty("numberOfElements") int numberOfElements,
      @JsonProperty("first") boolean first,
      @JsonProperty("last") boolean last,
      @JsonProperty("empty") boolean empty) {
    this.content = (content != null) ? content : List.of();
    this.totalElements = totalElements;
    this.totalPages = totalPages;
    this.number = number;
    this.size = size;
    this.numberOfElements = numberOfElements;
    this.first = first;
    this.last = last;
    this.empty = empty;
  }

  /** The rows of this page. */
  public List<T> content() {
    return content;
  }

  /** How many rows match the query over every page. */
  public long total() {
    return totalElements;
  }

  /** This page's number, from 0. */
  public int number() {
    return number;
  }

  /** The page size asked for. */
  public int size() {
    return size;
  }

  public int totalPages() {
    return totalPages;
  }

  public boolean isFirst() {
    return first;
  }

  public boolean isLast() {
    return last;
  }

  public boolean isEmpty() {
    return empty;
  }

  /** Read the page after this one with the same query; empty when this is the last. */
  public Optional<Page<T>> next() {
    return (last || following == null)
        ? Optional.empty()
        : Optional.of(following.apply(number + 1));
  }

  Page<T> following(IntFunction<Page<T>> fetch) {
    this.following = fetch;
    return this;
  }

  @Override
  public String toString() {
    return "Page[number=" + number + ", size=" + size + ", total=" + totalElements + "]";
  }
}
