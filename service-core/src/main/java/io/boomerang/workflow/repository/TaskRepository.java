package io.boomerang.workflow.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import io.boomerang.common.entity.TaskEntity;
import io.boomerang.common.enums.TaskStatus;

public interface TaskRepository extends MongoRepository<TaskEntity, String> {

  boolean existsByName(String name);

  Integer countByNameAndStatus(String name, TaskStatus status);

  Optional<TaskEntity> findByName(String name);

  List<TaskEntity> findByNameIn(Collection<String> names);

  void deleteByName(String name);
}
