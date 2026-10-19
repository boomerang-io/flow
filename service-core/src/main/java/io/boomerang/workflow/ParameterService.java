package io.boomerang.workflow;

import io.boomerang.core.ParamLayerCache;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.util.ParameterUtil;
import io.boomerang.common.util.DataAdapterUtil;
import io.boomerang.common.util.DataAdapterUtil.FieldType;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.workflow.entity.GlobalParamEntity;
import io.boomerang.workflow.repository.GlobalParamRepository;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/*
 * CRUD for Global Params
 */
@Service
public class ParameterService {

  private final Logger LOGGER = LogManager.getLogger(getClass());

  private final GlobalParamRepository paramRepository;
  private final ParamLayerCache paramLayerCache;
  private final ObjectMapper objectMapper;

  public ParameterService(
      @Autowired GlobalParamRepository paramRepository,
      ParamLayerCache paramLayerCache,
      ObjectMapper objectMapper) {
    this.paramRepository = paramRepository;
    this.paramLayerCache = paramLayerCache;
    this.objectMapper = objectMapper;
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

  /**
   * Apply a JSON Merge Patch (RFC 7396) to the named global param: an absent member is unchanged, a
   * {@code null} member is cleared, any other member replaces the stored one. A secured value or
   * default sent as the redaction marker keeps the stored one. The name cannot change, and the
   * read-only {@code hiddenValue} marker is never stored.
   */
  public AbstractParam patch(String name, JsonNode patch) {
    if (patch == null || !patch.isObject()) {
      throw new BoomerangException(BoomerangError.PARAMS_INVALID_REQ);
    }
    JsonNode patchedName = patch.get("name");
    if (patchedName != null && !name.equals(patchedName.asString())) {
      throw new BoomerangException(BoomerangError.PARAMS_INVALID_REQ);
    }
    GlobalParamEntity entity =
        paramRepository
            .findOneByName(name)
            .orElseThrow(() -> new BoomerangException(BoomerangError.PARAMS_INVALID_REFERENCE));
    AbstractParam stored = new AbstractParam();
    BeanUtils.copyProperties(entity, stored);

    ObjectNode members = ((ObjectNode) patch).deepCopy();
    members.remove("name");
    members.remove("hiddenValue");
    ObjectNode merged = objectMapper.valueToTree(stored);
    mergePatch(merged, members);
    AbstractParam patched = objectMapper.treeToValue(merged, AbstractParam.class);
    if (FieldType.PASSWORD.value().equals(patched.getType())) {
      patched.setValue(keepMarker(patched.getValue(), stored.getValue()));
      patched.setDefaultValue(keepMarker(patched.getDefaultValue(), stored.getDefaultValue()));
    }

    BeanUtils.copyProperties(patched, entity, "id");
    entity = paramRepository.save(entity);
    paramLayerCache.evictAll();
    return convertToAbstractParamAndFilter(entity);
  }

  // The redaction marker keeps the stored secret (DataAdapterUtil.keepSecretValue); on a patch a null
  // is a clear, not a keep, so only the marker is handed to the rule.
  private static Object keepMarker(Object patched, Object stored) {
    return DataAdapterUtil.REDACTED.equals(patched)
        ? DataAdapterUtil.keepSecretValue(patched, stored)
        : patched;
  }

  // RFC 7396: a null member removes the target member, an object member merges into the target's
  // object (created when absent), and any other member replaces the target member.
  private static void mergePatch(ObjectNode target, JsonNode patch) {
    for (Map.Entry<String, JsonNode> member : patch.properties()) {
      JsonNode value = member.getValue();
      if (value.isNull()) {
        target.remove(member.getKey());
      } else if (value.isObject()) {
        ObjectNode child =
            (target.get(member.getKey()) instanceof ObjectNode existing)
                ? existing
                : target.putObject(member.getKey());
        mergePatch(child, value);
      } else {
        target.set(member.getKey(), value);
      }
    }
  }

  public AbstractParam create(AbstractParam request) {
    if (!Objects.isNull(request) && request.getName() != null) {
      if (!ParameterUtil.isValidParamName(request.getName())) {
        throw new BoomerangException(BoomerangError.PARAM_INVALID_NAME, request.getName());
      }

      // Ensure Name is unique
      if (paramRepository.countByName(request.getName()) > 0) {
        throw new BoomerangException(BoomerangError.PARAMS_NON_UNIQUE_REFERENCE);
      }
      LOGGER.debug("Requested GlobalParamEntity: {}", request.getName());

      // Nothing is stored yet, so a redaction marker sent on create is no value, never the secret.
      DataAdapterUtil.keepParamSpecValueByFieldType(
          List.of(request), List.of(), FieldType.PASSWORD.value());
      GlobalParamEntity entity = new GlobalParamEntity();
      BeanUtils.copyProperties(request, entity, "id");
      LOGGER.debug("Creating GlobalParamEntity: {}", entity.getName());
      entity = paramRepository.save(entity);
      paramLayerCache.evictAll();
      LOGGER.debug("Saving GlobalParamEntity: {} ({})", entity.getName(), entity.getId());
      return convertToAbstractParamAndFilter(entity);
    }
    throw new BoomerangException(BoomerangError.PARAMS_INVALID_REQ);
  }

  public void delete(String name) {
    if (Objects.isNull(name) || name.isBlank()) {
      throw new BoomerangException(BoomerangError.PARAMS_INVALID_REQ);
    }
    if (paramRepository.countByName(name) == 0) {
      throw new BoomerangException(BoomerangError.PARAMS_INVALID_REFERENCE);
    }
    paramRepository.deleteByName(name);
    paramLayerCache.evictAll();
  }

  /*
   * Converts from GlobalParamEntity to AbstractParam and filters out a secured value and default
   */
  private AbstractParam convertToAbstractParamAndFilter(GlobalParamEntity entity) {
    AbstractParam param = new AbstractParam();
    BeanUtils.copyProperties(entity, param);
    LOGGER.debug("Copied GlobalParamEntity to AbstractParam: {}", param.getName());
    param = DataAdapterUtil.filterAbstractParam(param, false, FieldType.PASSWORD.value());
    param = DataAdapterUtil.filterAbstractParam(param, true, FieldType.PASSWORD.value());
    LOGGER.debug("Converted GlobalParamEntity to AbstractParam: {}", param.getName());
    return param;
  }
}
