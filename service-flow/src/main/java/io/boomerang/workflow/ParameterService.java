package io.boomerang.workflow;

import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.util.DataAdapterUtil;
import io.boomerang.common.util.DataAdapterUtil.FieldType;
import io.boomerang.error.BoomerangError;
import io.boomerang.error.BoomerangException;
import io.boomerang.workflow.entity.GlobalParamEntity;
import io.boomerang.workflow.repository.GlobalParamRepository;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/*
 * CRUD for Global Params
 */
@Service
public class ParameterService {

  private final Logger LOGGER = LogManager.getLogger(getClass());

  private final GlobalParamRepository paramRepository;

  public ParameterService(@Autowired GlobalParamRepository paramRepository) {
    this.paramRepository = paramRepository;
  }

  public List<AbstractParam> getAll() {
    List<GlobalParamEntity> entities = paramRepository.findAll();
    List<AbstractParam> params = new LinkedList<>();
    for (GlobalParamEntity entity : entities) {
      params.add(convertToAbstractParamAndFilter(entity));
    }
    return params;
  }

  public List<AbstractParam> getAllUnfiltered() {
    List<GlobalParamEntity> entities = paramRepository.findAll();
    List<AbstractParam> params = new LinkedList<>();
    for (GlobalParamEntity entity : entities) {
      AbstractParam param = new AbstractParam();
      BeanUtils.copyProperties(entity, param, "id");
      params.add(param);
    }
    return params;
  }

  public AbstractParam update(AbstractParam param) {
    if (!Objects.isNull(param) && param.getName() != null) {
      Optional<GlobalParamEntity> optParamEntity = paramRepository.findOneByName(param.getName());
      if (!optParamEntity.isEmpty()) {
        // Copy updatedParam to ParamEntity except for ID (requester should not know ID);
        BeanUtils.copyProperties(param, optParamEntity.get(), "id");
        GlobalParamEntity entity = paramRepository.save(optParamEntity.get());
        return convertToAbstractParamAndFilter(entity);
      }
    }
    throw new BoomerangException(BoomerangError.PARAMS_INVALID_REFERENCE);
  }

  public AbstractParam create(AbstractParam request) {
    if (!Objects.isNull(request) && request.getName() != null) {

      // Ensure Name is unique
      if (paramRepository.countByName(request.getName()) > 0) {
        throw new BoomerangException(BoomerangError.PARAMS_NON_UNIQUE_REFERENCE);
      }
      LOGGER.debug("Requested GlobalParamEntity: " + request.toString());

      GlobalParamEntity entity = new GlobalParamEntity();
      BeanUtils.copyProperties(request, entity, "id");
      LOGGER.debug("Creating GlobalParamEntity: " + entity.toString());
      entity = paramRepository.save(entity);
      LOGGER.debug("Saving GlobalParamEntity: " + entity.toString());
      return convertToAbstractParamAndFilter(entity);
    }
    throw new BoomerangException(BoomerangError.PARAMS_INVALID_REFERENCE);
  }

  public void delete(String name) {
    if (!Objects.isNull(name) && name != null && paramRepository.countByName(name) > 0) {
      paramRepository.deleteByName(name);
    }
    throw new BoomerangException(BoomerangError.PARAMS_INVALID_REFERENCE);
  }

  /*
   * Converts from GlobalParamEntity to AbstractParam and filters out secure values
   */
  private AbstractParam convertToAbstractParamAndFilter(GlobalParamEntity entity) {
    AbstractParam param = new AbstractParam();
    BeanUtils.copyProperties(entity, param);
    LOGGER.debug("Copied GlobalParamEntity to AbstractParam: " + param.toString());
    param = DataAdapterUtil.filterAbstractParam(param, false, FieldType.PASSWORD.value());
    LOGGER.debug("Converted GlobalParamEntity to AbstractParam: " + param.toString());
    return param;
  }
}
