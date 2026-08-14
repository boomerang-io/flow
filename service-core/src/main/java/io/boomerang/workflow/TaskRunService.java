package io.boomerang.workflow;

import io.boomerang.client.EngineClient;
import io.boomerang.error.BoomerangError;
import io.boomerang.error.BoomerangException;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Service
public class TaskRunService {

  private static final Logger LOGGER = LogManager.getLogger();

  @Value("${flow.agent.streamlogs.url}")
  private String getStreamDownloadPath;

  private final RestTemplate restTemplate;
  private final EngineClient engineClient;
  private final ParameterManager parameterManager;

  public TaskRunService(
      @Qualifier("internalRestTemplate") RestTemplate restTemplate,
      EngineClient engineClient,
      ParameterManager parameterManager) {
    this.restTemplate = restTemplate;
    this.engineClient = engineClient;
    this.parameterManager = parameterManager;
  }

  public StreamingResponseBody streamLog(String taskRunId) {
    if (!Objects.isNull(taskRunId) && !taskRunId.isBlank()) {
      // TODO: check parent has valid relationship
      //      Optional<RelationshipEntity> rel =
      // relationshipService.getRelationship(RelationshipRef.TASKRUN, taskRunId,
      // RelationshipType.BELONGSTO);
      //      if (!rel.isEmpty()) {
      LOGGER.info("Getting TaskRun[{}] log...", taskRunId);
      return engineClient.streamTaskRunLog(taskRunId);
    }
    throw new BoomerangException(BoomerangError.TASKRUN_INVALID_REF);
  }
  //
  //  private ResponseExtractor<Void> getResponseExtractorForRemovalList(List<String> maskWordList,
  //      OutputStream outputStream, PrintWriter printWriter) {
  //    if (maskWordList.isEmpty()) {
  //      LOGGER.debug("Remove word list empty, moving on.");
  //      return restTemplateResponse -> {
  //        InputStream is = restTemplateResponse.getBody();
  //        int nRead;
  //        byte[] data = new byte[1024];
  //        while ((nRead = is.read(data, 0, data.length)) != -1) {
  //          outputStream.write(data, 0, nRead);
  //        }
  //        return null;
  //      };
  //    } else {
  //      LOGGER.info("Streaming response from controller and processing");
  //      return restTemplateResponse -> {
  //        try {
  //          InputStream is = restTemplateResponse.getBody();
  //          Reader reader = new InputStreamReader(is);
  //          BufferedReader bufferedReader = new BufferedReader(reader);
  //          String input = null;
  //          while ((input = bufferedReader.readLine()) != null) {
  //
  //            printWriter.println(sanitizeInput(input, maskWordList));
  //            if (!input.isBlank()) {
  //              printWriter.flush();
  //            }
  //          }
  //        } catch (Exception e) {
  //          LOGGER.error("Error streaming logs, displaying exception and moving on.");
  //          LOGGER.error(ExceptionUtils.getStackTrace(e));
  //        } finally {
  //          printWriter.close();
  //        }
  //        return null;
  //      };
  //    }
  //  }
  //
  //  //TODO: verify this - i doubt we need to do it this way anymore. The TaskRun has the resolved
  // parameters. So we can just check type and value and replace.
  //  private List<String> buildRemovalList(String teamId, String workflowRunId, String taskRunId,
  // TaskRun taskRun) {
  //    List<String> removalList = new LinkedList<>();
  //    //TODO: it has to be workflowId not workflowRunId
  //    ParamLayers paramLayers =
  //        parameterManager.buildParamLayers(teamId, workflowRunId);
  //    Map<String, Object> map = paramLayers.getFlatMap();
  //
  //    if (taskRun != null && taskRun.getParams() != null) {
  //      for (RunParam param : taskRun.getParams()) {
  //        if ("password".equals(param.getType().toString())) {
  //          LOGGER.debug("Found a secured property being used - Name: {}", param.getName());
  //          // Assume that Password types are of String Param type
  //          String value = (String) map.get(param.getName());
  //          if (!value.isBlank()) {
  //            removalList.add(value);
  //          }
  //        }
  //      }
  //    }
  //
  //    LOGGER.debug("Param removal list");
  //    for (String item : removalList) {
  //      LOGGER.debug("Item: {}", item);
  //    }
  //    return removalList;
  //  }
  //
  //  private String sanitizeInput(String input, List<String> removeList) {
  //    for (String value : removeList) {
  //      input = input.replaceAll(Pattern.quote(value), "******");
  //    }
  //    return input;
  //  }

}
