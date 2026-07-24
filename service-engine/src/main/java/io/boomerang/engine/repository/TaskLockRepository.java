package io.boomerang.engine.repository;

import io.boomerang.engine.entity.TaskLockEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface TaskLockRepository
    extends MongoRepository<TaskLockEntity, String>, TaskLockRepositoryCustom {}
