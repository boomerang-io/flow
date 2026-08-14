package io.boomerang.workflow.repository;

import io.boomerang.workflow.entity.GlobalParamEntity;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface GlobalParamRepository extends MongoRepository<GlobalParamEntity, String> {

  @Override
  Optional<GlobalParamEntity> findById(String id);

  Optional<GlobalParamEntity> findOneByName(String name);

  void deleteByName(String name);

  Integer countByName(String name);
}
