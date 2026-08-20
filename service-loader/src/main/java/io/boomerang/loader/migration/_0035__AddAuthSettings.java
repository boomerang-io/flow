package io.boomerang.loader.migration;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.List;
import org.bson.Document;

/**
 * Seeds the {@code auth} settings document ({@code oidc.issuer}, {@code oidc.clientId}) - the
 * trusted-issuer configuration surface for the direct OIDC login path of {@code POST
 * /api/v2/auth/exchange} (specifications/authentication.md §1/§5). Both values seed empty; an
 * operator fills them in to enable local IDPZero (or any single trusted OIDC issuer) login. This
 * is new attack surface - every value configured here is an issuer Flow will trust to mint
 * identities - so it seeds empty rather than defaulting to anything live.
 *
 * <p>A fresh install picks the document up via {@code _0021__SeedSettings} reading the updated
 * {@code seed/settings.json}. An install that already ran {@code _0021} before this document
 * existed never sees it appear on its own - {@code _0021} only inserts documents absent by {@code
 * _id}/{@code key}, and it never re-runs - so this unit backfills the same document directly,
 * exactly as {@code _0029__AddGitHubOAuthSettings} backfilled the GitHub OAuth settings onto an
 * already-migrated install.
 *
 * <p>Idempotent: guarded on the seed document's own {@code _id} OR {@code key="auth"}, so a
 * second run (or a fresh install where {@code _0021} already inserted it) finds it present and
 * changes nothing.
 */
@Change(id = "0035-add-auth-settings", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0035__AddAuthSettings {

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    List<Document> settings = SeedResources.load("seed/settings.json");
    Document authSetting =
        settings.stream()
            .filter(d -> "auth".equals(d.getString("key")))
            .findFirst()
            .orElseThrow(
                () -> new IllegalStateException("seed/settings.json is missing the 'auth' document"));

    boolean inserted =
        SeedResources.insertIfAbsent(
            db,
            names.resolve("settings"),
            Filters.or(Filters.eq("_id", authSetting.get("_id")), Filters.eq("key", "auth")),
            authSetting);
    SeedResources.logSeeded("settings(auth)", inserted ? 1 : 0, 1);
  }

  @Rollback
  public void rollback() {
    // Settings carry operator-configured values once an install is live - forward-only, matching
    // the other settings units in this chain.
  }
}
