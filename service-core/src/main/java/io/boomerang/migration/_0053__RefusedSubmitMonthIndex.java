package io.boomerang.migration;

import com.mongodb.client.MongoDatabase;
import io.flamingock.api.RecoveryStrategy;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Recovery;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Builds the partial index {@code refused_submit_month} on the workflow runs a refused submit left
 * behind as an entry ({@code statusReason} {@code SubmitRefused}), keyed by workflow and creation
 * date. The monthly run count takes those entries back out, and with this index that is a count of
 * index keys over the few entries there are. An empty or a v3 database already has it from {@link
 * _0021__Indexes}, and a second run finds it.
 */
@Change(id = "0053-refused-submit-month-index", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0053__RefusedSubmitMonthIndex {

  private static final Logger LOG = LoggerFactory.getLogger(_0053__RefusedSubmitMonthIndex.class);

  static final _0021__Indexes.Index REFUSED_SUBMIT_MONTH =
      _0021__Indexes.INVENTORY.stream()
          .filter(index -> "workflow_runs".equals(index.collection()))
          .filter(index -> "refused_submit_month".equals(index.name()))
          .findFirst()
          .orElseThrow();

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    boolean ensured =
        MigrationUtils.ensureIndex(
            db,
            CollectionNames.from(env).resolve(REFUSED_SUBMIT_MONTH.collection()),
            REFUSED_SUBMIT_MONTH.name(),
            REFUSED_SUBMIT_MONTH.keys(),
            REFUSED_SUBMIT_MONTH.options());
    LOG.info(
        ensured ? "{} index ensured" : "{} index could not be built; the count reads without it",
        REFUSED_SUBMIT_MONTH.name());
  }

  @Rollback
  public void rollback() {
    // An index nothing depends on for correctness - forward-only, like every other unit here.
  }
}
