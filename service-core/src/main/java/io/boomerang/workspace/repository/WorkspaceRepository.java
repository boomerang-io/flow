package io.boomerang.workspace.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import io.boomerang.workspace.entity.WorkspaceEntity;

public interface WorkspaceRepository extends MongoRepository<WorkspaceEntity, String> {

  Optional<WorkspaceEntity> findByNameIgnoreCase(String name);
  
  Long countByNameIgnoreCase(String name);

  @Override
  Optional<WorkspaceEntity> findById(String id);

  List<WorkspaceEntity> findByIdIn(List<String> ids);
  
  void deleteByName(String name);
}
