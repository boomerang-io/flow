package io.boomerang.workspace;

import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.util.DataAdapterUtil.FieldType;
import io.boomerang.common.util.ParameterUtil;
import io.boomerang.core.ParamLayerCache;
import io.boomerang.workspace.entity.WorkspaceEntity;
import io.boomerang.workspace.repository.WorkspaceRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

/*
 * The parameters stored on a workspace, written the same way in both run modes: a request replaces
 * parameters by name, a secured value sent blank keeps the stored secret, and every change evicts
 * the cached parameter layers so the next run reads it.
 */
@Service
public class WorkspaceParameterService {
  private static final Logger LOGGER = LogManager.getLogger();

  private final WorkspaceRepository workspaceRepository;
  private final ParamLayerCache paramLayerCache;

  public WorkspaceParameterService(
      WorkspaceRepository workspaceRepository, ParamLayerCache paramLayerCache) {
    this.workspaceRepository = workspaceRepository;
    this.paramLayerCache = paramLayerCache;
  }

  /*
   * Creates or Updates Workspace Parameters
   */
  public List<AbstractParam> createOrUpdateParameters(
      List<AbstractParam> parameters, List<AbstractParam> request) {
    if (!request.isEmpty()) {
      LOGGER.debug("Starting Parameters: " + parameters.toString());
      // A secured parameter's value is never returned to the caller, so a request that only
      // edits another field arrives with a blank value - carry the stored secret forward
      // instead of letting the wholesale replacement below wipe it out.
      Map<String, AbstractParam> existingByName =
          parameters.stream()
              .collect(Collectors.toMap(AbstractParam::getName, p -> p, (a, b) -> a));
      request.forEach(
          p -> {
            AbstractParam existing = existingByName.get(p.getName());
            if (existing != null
                && FieldType.PASSWORD.value().equals(existing.getType())
                && isBlankValue(p.getValue())) {
              p.setValue(existing.getValue());
            }
          });

      List<String> names = request.stream().map(AbstractParam::getName).toList();
      names.stream()
          .filter(name -> !ParameterUtil.isValidParamName(name))
          .findFirst()
          .ifPresent(
              name -> {
                throw new BoomerangException(BoomerangError.PARAM_INVALID_NAME, name);
              });
      // Check if parameter exists and remove
      parameters =
          parameters.stream()
              .filter(p -> !names.contains(p.getName()))
              .collect(Collectors.toList());

      // Add all new / updated params
      parameters.addAll(request);
    }
    LOGGER.debug("Ending Parameters: " + parameters.toString());
    return parameters;
  }

  /*
   * Delete a workspace's parameter by name. A workspace with no parameter of that name is
   * PARAMS_INVALID_REFERENCE.
   */
  public void deleteParameter(WorkspaceEntity workspaceEntity, String name) {
    if (workspaceEntity.getParameters() != null) {
      List<AbstractParam> parameters = workspaceEntity.getParameters();
      Optional<AbstractParam> optionalParameter =
          parameters.stream().filter(p -> p.getName().equals(name)).findAny();
      if (optionalParameter.isPresent()) {
        parameters.remove(optionalParameter.get());
        workspaceEntity.setParameters(parameters);
        workspaceRepository.save(workspaceEntity);
        paramLayerCache.evictAll();
      } else {
        throw new BoomerangException(BoomerangError.PARAMS_INVALID_REFERENCE);
      }
    }
  }

  // Blank covers both a null value (a filtered secured value is never serialised) and an empty
  // string (a client that round-trips a form field literally).
  private static boolean isBlankValue(Object value) {
    return value == null || (value instanceof String s && s.isBlank());
  }
}
