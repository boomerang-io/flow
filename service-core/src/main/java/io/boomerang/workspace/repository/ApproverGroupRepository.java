package io.boomerang.workspace.repository;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import io.boomerang.workspace.entity.ApproverGroupEntity;

public interface ApproverGroupRepository extends MongoRepository<ApproverGroupEntity, String> {
  
  List<ApproverGroupEntity> findByIdIn(List<String> ids);
  
}
