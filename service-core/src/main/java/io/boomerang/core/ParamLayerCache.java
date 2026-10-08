package io.boomerang.core;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.boomerang.common.entity.WorkflowRevisionEntity;
import io.boomerang.common.model.ParamLayers;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Per-instance, short-lived cache of the global, workspace and context parameter layers, keyed by
 * workspace, workflow and revision, and of revisions by id - so the tasks of a run, and of every
 * run of the same revision, admitted within the TTL share one read of each store. Every write to a
 * global or workspace parameter, a setting, a workflow or a revision clears it on the writing
 * instance; other instances see the write once their entries age out (the TTL). A TTL of zero
 * disables it.
 */
@Component
public class ParamLayerCache {

  private record Layers(
      Map<String, Object> global, Map<String, Object> workspace, Map<String, Object> context) {}

  private final boolean enabled;
  private final Cache<String, Layers> layers;
  private final Cache<String, WorkflowRevisionEntity> revisions;

  public ParamLayerCache(
      @Value("${flow.parameters.layer-cache.enabled:true}") boolean enabled,
      @Value("${flow.parameters.layer-cache.ttl:10s}") Duration ttl,
      @Value("${flow.parameters.layer-cache.max-size:1000}") long maxSize) {
    this.enabled = enabled && !ttl.isZero();
    this.layers = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(maxSize).build();
    this.revisions = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(maxSize).build();
  }

  /**
   * Return the layers for the key, building them on a miss. Each call gets its own maps, because
   * the engine adds per-run and per-task keys to them.
   */
  public ParamLayers layers(String key, Supplier<ParamLayers> build) {
    if (!enabled) {
      return build.get();
    }
    Layers cached =
        layers.get(
            key,
            k -> {
              ParamLayers built = build.get();
              return new Layers(
                  built.getGlobalParams(),
                  built.getWorkspaceParams(),
                  built.getContextParams());
            });
    ParamLayers copy = new ParamLayers();
    copy.setGlobalParams(new HashMap<>(cached.global()));
    copy.setWorkspaceParams(new HashMap<>(cached.workspace()));
    copy.setContextParams(new HashMap<>(cached.context()));
    return copy;
  }

  /**
   * Return the revision with this id, reading it on a miss; null when it does not exist. The
   * instance is shared between callers, which read it and never change it.
   */
  public WorkflowRevisionEntity revision(String id, Function<String, WorkflowRevisionEntity> read) {
    return (enabled ? revisions.get(id, read) : read.apply(id));
  }

  /** Forget every entry - called on each write to a store the layers are read from. */
  public void evictAll() {
    layers.invalidateAll();
    revisions.invalidateAll();
  }
}
