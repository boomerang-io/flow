package io.boomerang.tests.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import io.boomerang.workflow.GlobalParamController;
import io.boomerang.misc.FlowTests;
import io.boomerang.agent.config.model.GlobalConfig;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@WithMockUser(roles = {"admin"})
@WithUserDetails("mdroy@us.ibm.com")
public class GlobalConfigControllerTests extends FlowTests {

  @Autowired private GlobalParamController globalConfigController;

  @Test
  public void testGlobalConfigUpdate() {
    GlobalConfig newConfig = new GlobalConfig();
    newConfig.setKey("test");
    newConfig.setName("Test");
    newConfig.setDescription("Test Description");
    newConfig.setDefaultValue("test");

    globalConfigController.createNewGlobalConfig(newConfig);

    List<GlobalConfig> allConfigs = this.globalConfigController.getAllGlobalConfigurations();
    assertEquals(1, allConfigs.size());

    GlobalConfig updatedConfig = allConfigs.get(0);
    updatedConfig.setDescription("New Description");

    updatedConfig =
        this.globalConfigController.updateGlobalConfig(updatedConfig, updatedConfig.getId());

    assertEquals("New Description", updatedConfig.getDescription());

    this.globalConfigController.deleteConfiguration(updatedConfig.getId());
  }
}
