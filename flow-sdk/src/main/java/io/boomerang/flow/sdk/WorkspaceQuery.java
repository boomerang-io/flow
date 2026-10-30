package io.boomerang.flow.sdk;

import io.boomerang.flow.sdk.model.WorkspaceStatus;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What {@link Workspaces#query} narrows by, mapped to the route's parameters: a prefix search on
 * name and display name, labels, statuses, names and {@code externalRef}. Paging is {@code page}
 * from 0 and {@code limit}; the sort is {@code sort} (default {@code name}) and {@code order}.
 */
public final class WorkspaceQuery {

  private String search;
  private final List<String> labels = new ArrayList<>();
  private final List<WorkspaceStatus> statuses = new ArrayList<>();
  private final List<String> names = new ArrayList<>();
  private String externalRef;
  private Integer page;
  private Integer limit;
  private Order order;
  private String sort;

  private WorkspaceQuery() {}

  /** No filter: every workspace the token reaches. */
  public static WorkspaceQuery all() {
    return new WorkspaceQuery();
  }

  /** Workspaces whose name or display name starts with this, ignoring case. */
  public WorkspaceQuery search(String search) {
    this.search = search;
    return this;
  }

  /** Workspaces carrying every one of these labels, each as {@code key=value}. */
  public WorkspaceQuery labels(String... labels) {
    this.labels.addAll(Arrays.asList(labels));
    return this;
  }

  public WorkspaceQuery statuses(WorkspaceStatus... statuses) {
    this.statuses.addAll(Arrays.asList(statuses));
    return this;
  }

  /** Workspaces with these names. */
  public WorkspaceQuery names(String... names) {
    this.names.addAll(Arrays.asList(names));
    return this;
  }

  /** The one workspace carrying this external reference. */
  public WorkspaceQuery externalRef(String externalRef) {
    this.externalRef = externalRef;
    return this;
  }

  /** The page to read, from 0, of {@code limit} workspaces each. */
  public WorkspaceQuery page(int page, int limit) {
    this.page = page;
    this.limit = limit;
    return this;
  }

  /** The sort field, {@code name} or {@code displayName}, and order. */
  public WorkspaceQuery sort(String sort, Order order) {
    this.sort = sort;
    this.order = order;
    return this;
  }

  WorkspaceQuery withPage(int page) {
    WorkspaceQuery copy = all();
    copy.search = search;
    copy.labels.addAll(labels);
    copy.statuses.addAll(statuses);
    copy.names.addAll(names);
    copy.externalRef = externalRef;
    copy.page = page;
    copy.limit = limit;
    copy.order = order;
    copy.sort = sort;
    return copy;
  }

  Params params() {
    return Params.none()
        .add("search", search)
        .addAll("labels", labels)
        .addAll("statuses", statuses)
        .addAll("workspaces", names)
        .add("externalRef", externalRef)
        .add("limit", limit)
        .add("page", page)
        .add("order", order)
        .add("sort", sort);
  }
}
