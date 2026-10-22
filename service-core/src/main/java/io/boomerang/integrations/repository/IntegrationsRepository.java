package io.boomerang.integrations.repository;

import java.util.List;
import java.util.Optional;

import io.boomerang.integrations.entity.IntegrationsEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface IntegrationsRepository extends MongoRepository<IntegrationsEntity, String> {

  List<IntegrationsEntity> findByType(String type); 
  
  Optional<IntegrationsEntity> findByRef(String ref);
  
  Optional<IntegrationsEntity> findByIdAndType(String id, String type);
}

