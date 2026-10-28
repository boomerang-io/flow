package io.boomerang.integrations.repository;

import io.boomerang.integrations.entity.IntegrationTemplateEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface IntegrationTemplateRepository
    extends MongoRepository<IntegrationTemplateEntity, String> {

  List<IntegrationTemplateEntity> findAllByStatus(String status);

  Optional<IntegrationTemplateEntity> findFirstByType(String type);
}
