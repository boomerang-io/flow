package io.boomerang.workspace.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import io.boomerang.workspace.entity.WorkspaceEntity;

public interface WorkspaceRepository extends MongoRepository<WorkspaceEntity, String> {

  Optional<WorkspaceEntity> findByNameIgnoreCase(String name);

  /**
   * The workspace by id, else by name ignoring case. Callers hold either: an API path carries the
   * name, while a relationship edge (a workflow's or schedule's parent) yields the id.
   */
  default Optional<WorkspaceEntity> findByIdOrName(String idOrName) {
    if (idOrName == null || idOrName.isBlank()) {
      return Optional.empty();
    }
    return findById(idOrName).or(() -> findByNameIgnoreCase(idOrName));
  }

  // The workspace mirroring an embedding product's own record, by that product's id. Served by
  // the sparse unique external_ref index.
  Optional<WorkspaceEntity> findByExternalRef(String externalRef);
  
  Long countByNameIgnoreCase(String name);

  @Override
  Optional<WorkspaceEntity> findById(String id);

  List<WorkspaceEntity> findByIdIn(List<String> ids);
  
  void deleteByName(String name);
}
