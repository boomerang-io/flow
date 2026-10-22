package io.boomerang.core.model;

import lombok.Data;
import org.springframework.beans.BeanUtils;
import io.boomerang.core.entity.SettingEntity;

@Data
public class Setting extends SettingEntity {

  public Setting() {
    super();
  }

  public Setting(SettingEntity entity) {
    BeanUtils.copyProperties(entity, this); // NOSONAR
  }
}
