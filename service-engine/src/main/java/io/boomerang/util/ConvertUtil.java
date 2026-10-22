package io.boomerang.util;

import io.boomerang.common.entity.WorkflowEntity;
import io.boomerang.common.entity.WorkflowRevisionEntity;
import io.boomerang.common.model.Workflow;
import io.boomerang.error.BoomerangError;
import io.boomerang.error.BoomerangException;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;
import org.springframework.beans.BeanUtils;

/*
 * This class will do the BeanUtils.copyproperties from Entity to Model ensuring its not in the
 * shared models and the entities don't need to be copied to the other service
 */
public class ConvertUtil {

  /*
   * Creates a Workflow from WorkflowEntity and WorkflowRevisionEntity
   *
   * Does not copy / convert the stored Tasks onto the Workflow. If you want the Tasks you need to run
   * workflow.setTasks(TaskMapper.revisionTasksToListOfTasks(wfRevisionEntity.getTasks()));
   */
  public static Workflow wfEntityToModel(
      WorkflowEntity wfEntity, WorkflowRevisionEntity wfRevisionEntity) {
    Workflow model = new Workflow();
    BeanUtils.copyProperties(wfEntity, model);
    BeanUtils.copyProperties(wfRevisionEntity, model, "id");
    return model;
  }

  /*
   * Generic method to convert from entity to specified Model and copy elements.
   */
  public static <E, M> M entityToModel(E entity, Class<M> modelClass) {
    if (Objects.isNull(entity) || Objects.isNull(modelClass)) {
      throw new BoomerangException(BoomerangError.DATA_CONVERSION_FAILED);
    }

    try {
      M model = modelClass.getDeclaredConstructor().newInstance();
      BeanUtils.copyProperties(entity, model);
      return model;
    } catch (NoSuchMethodException
        | SecurityException
        | InstantiationException
        | IllegalAccessException
        | IllegalArgumentException
        | InvocationTargetException ex) {
      throw new BoomerangException(ex, BoomerangError.DATA_CONVERSION_FAILED);
    }
  }
}
