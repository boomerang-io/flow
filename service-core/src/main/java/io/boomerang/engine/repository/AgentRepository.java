package io.boomerang.engine.repository;

import io.boomerang.engine.entity.AgentEntity;
import java.util.Date;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;

public interface AgentRepository extends MongoRepository<AgentEntity, String> {

  boolean existsById(String id);

  @Query("{ '_id': ?0 }")
  @Update("{ '$set': { 'lastConnectedDate': ?1 } }")
  void updateLastConnected(String agentId, Date lastConnected);

  @Query(value = "{ '_id': ?0 }", fields = "{ 'taskTypes': 1 }")
  AgentEntity findTaskTypesByAgentId(String agentId);
}
