package io.boomerang.core.repository;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import io.boomerang.core.entity.RoleEntity;

public interface RoleRepository extends MongoRepository<RoleEntity, String> {
  
  List<RoleEntity> findByType(String type);
  
  RoleEntity findByTypeAndName(String type, String name);
  
}
