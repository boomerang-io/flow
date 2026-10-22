package io.boomerang.flow.sdk;

import io.boomerang.flow.sdk.model.ActionStatus;
import io.boomerang.flow.sdk.model.ActionType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What {@link Actions#query} narrows by, mapped to the route's parameters: type, status, workflow
 * and creation date, paged and sorted (by {@code creationDate} unless another field is named).
 *
 * <pre>{@code
 * ActionQuery.all().types(ActionType.approval).statuses(ActionStatus.submitted).workflows(workflowId)
 * }</pre>
 */
public final class ActionQuery {

  private final List<ActionType> types = new ArrayList<>();
  private final List<ActionStatus> statuses = new ArrayList<>();
  private final List<String> workflows = new ArrayList<>();
  private Instant from;
  private Instant to;
  private Integer page;
  private Integer limit;
  private Order order;
  private String sort;

  private ActionQuery() {}

  public static ActionQuery all() {
    return new ActionQuery();
  }

  public ActionQuery types(ActionType... types) {
    this.types.addAll(Arrays.asList(types));
    return this;
  }

  public ActionQuery statuses(ActionStatus... statuses) {
    this.statuses.addAll(Arrays.asList(statuses));
    return this;
  }

  /** Actions of these workflows, by id. */
  public ActionQuery workflows(String... ids) {
    this.workflows.addAll(Arrays.asList(ids));
    return this;
  }

  public ActionQuery from(Instant from) {
    this.from = from;
    return this;
  }

  public ActionQuery to(Instant to) {
    this.to = to;
    return this;
  }

  /** The page to read, from 0, and its size; the server's default is the first 10. */
  public ActionQuery page(int page, int limit) {
    this.page = page;
    this.limit = limit;
    return this;
  }

  /** The order on the sort field; the server's default is descending. */
  public ActionQuery order(Order order) {
    this.order = order;
    return this;
  }

  /** The field to sort on; the server's default is {@code creationDate}. */
  public ActionQuery sort(String field) {
    this.sort = field;
    return this;
  }

  ActionQuery withPage(int page) {
    ActionQuery copy = all();
    copy.types.addAll(types);
    copy.statuses.addAll(statuses);
    copy.workflows.addAll(workflows);
    copy.from = from;
    copy.to = to;
    copy.page = page;
    copy.limit = limit;
    copy.order = order;
    copy.sort = sort;
    return copy;
  }

  Params params() {
    return Params.none()
        .addAll("types", types)
        .addAll("statuses", statuses)
        .addAll("workflows", workflows)
        .add("fromDate", (from != null) ? from.toEpochMilli() : null)
        .add("toDate", (to != null) ? to.toEpochMilli() : null)
        .add("page", page)
        .add("limit", limit)
        .add("order", order)
        .add("sort", sort);
  }
}
