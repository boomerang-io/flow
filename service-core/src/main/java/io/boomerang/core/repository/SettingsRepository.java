package io.boomerang.core.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import io.boomerang.core.entity.SettingEntity;

public interface SettingsRepository extends MongoRepository<SettingEntity, String> {

  SettingEntity findOneByKey(String key);

}
