package io.boomerang.tests.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import io.boomerang.workflow.TeamController;
import io.boomerang.workflow.entity.TeamEntity;
import io.boomerang.workflow.model.CurrentQuotas;
import io.boomerang.workflow.model.Quotas;
import io.boomerang.data.model.TeamParameter;
import io.boomerang.misc.FlowTests;
import io.boomerang.workflow.model.TeamRequest;
import io.boomerang.workflow.model.WorkflowSummary;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@WithMockUser(roles = {"admin"})
@WithUserDetails("mdroy@us.ibm.com")
public class TeamControllerTests extends FlowTests {

  @Autowired
  private TeamController controller;

  @Test
  public void testGetTeams() {
    Assertions.assertEquals(3, controller.getTeams().size());

    Assertions.assertEquals(Integer.valueOf(15),
        controller.getTeams().get(0).getQuotas().getMaxWorkflowCount());
    Assertions.assertEquals(Integer.valueOf(150),
        controller.getTeams().get(0).getQuotas().getMaxWorkflowRunMonthly());
    Assertions.assertEquals(Integer.valueOf(10),
        controller.getTeams().get(0).getQuotas().getMaxWorkflowStorage());
    Assertions.assertEquals(Integer.valueOf(60),
        controller.getTeams().get(0).getQuotas().getMaxWorkflowRunDuration());
    Assertions.assertEquals(Integer.valueOf(2),
        controller.getTeams().get(0).getQuotas().getMaxConcurrentRuns());

    Assertions.assertEquals(Integer.valueOf(9),
        controller.getTeams().get(0).getWorkflowQuotas().getCurrentWorkflowCount());
    Assertions.assertEquals(Integer.valueOf(3),
        controller.getTeams().get(0).getWorkflowQuotas().getCurrentConcurrentRuns());
    Assertions.assertEquals(Integer.valueOf(0),
        controller.getTeams().get(0).getWorkflowQuotas().getCurrentRunTotalDuration());
    Assertions.assertEquals(Integer.valueOf(0),
        controller.getTeams().get(0).getWorkflowQuotas().getCurrentTotalWorkflowStorage());
    Assertions.assertEquals(firstOfNextMonth(),
        controller.getTeams().get(0).getWorkflowQuotas().getMonthlyResetDate());
  }

  @Test
  public void testCreateFlowTeam() {
    TeamRequest request = new TeamRequest();
    request.setName("WDC2 ISE Dev");
    request.setCreatedGroupId("5cedb53261a23a0001e4c1b6");

    controller.createCiTeam(request);

    Assertions.assertEquals(4, controller.getTeams().size());

    Assertions.assertEquals("WDC2 ISE Dev", controller.getTeams().get(3).getName());
    Assertions.assertEquals(Integer.valueOf(10),
        controller.getTeams().get(3).getQuotas().getMaxWorkflowCount());
    Assertions.assertEquals(Integer.valueOf(4),
        controller.getTeams().get(3).getQuotas().getMaxConcurrentRuns());
    Assertions.assertEquals(Integer.valueOf(100),
        controller.getTeams().get(3).getQuotas().getMaxWorkflowRunMonthly());
    Assertions.assertEquals(Integer.valueOf(5),
        controller.getTeams().get(3).getQuotas().getMaxWorkflowStorage());
    Assertions.assertEquals(Integer.valueOf(30),
        controller.getTeams().get(3).getQuotas().getMaxWorkflowRunDuration());
    Assertions.assertEquals(Integer.valueOf(0),
        controller.getTeams().get(3).getWorkflowQuotas().getCurrentConcurrentRuns());
    Assertions.assertEquals(Integer.valueOf(0),
        controller.getTeams().get(3).getWorkflowQuotas().getCurrentWorkflowCount());
    Assertions.assertEquals(Integer.valueOf(0),
        controller.getTeams().get(3).getWorkflowQuotas().getCurrentRunTotalDuration());
    Assertions.assertEquals(Integer.valueOf(0),
        controller.getTeams().get(3).getWorkflowQuotas().getCurrentTotalWorkflowStorage());
    Assertions.assertEquals(firstOfNextMonth(),
        controller.getTeams().get(3).getWorkflowQuotas().getMonthlyResetDate());
  }

  @Test
  public void testGetAllTeamProperties() {
    List<TeamParameter> configs =
        controller.getAllTeamProperties("5d1a1841f6ca2c00014c4309");
    Assertions.assertEquals(0, configs.size());

    List<TeamParameter> configs2 =
        controller.getAllTeamProperties("5d1a1841f6ca2c00014c4302");
    Assertions.assertEquals(1, configs2.size());
  }

  @Test
  public void testDeleteTeamProperty() {
    controller.deleteParameters("5d1a1841f6ca2c00014c4302",
        "df5f5749-4d30-41c3-803e-56b54b768407");
    Assertions.assertEquals(0, controller.getAllTeamProperties("5d1a1841f6ca2c00014c4302").size());
  }

  @Test
  public void testUpdateTeamProperty() {
    Assertions.assertEquals(null,
        controller.getAllTeamProperties("5d1a1841f6ca2c00014c4302").get(0).getToken());

    TeamParameter property = new TeamParameter();
    property.setId("df5f5749-4d30-41c3-803e-56b54b768407");
    property.setToken("Updated Value");

    List<TeamParameter> updatedConfigs = controller.updateTeamProperty(
        "5d1a1841f6ca2c00014c4302", property, "df5f5749-4d30-41c3-803e-56b54b768407");
    Assertions.assertEquals("Updated Value", updatedConfigs.get(0).getToken());
  }

  @Test
  public void testCreateNewTeamProperty() {
    TeamParameter property = new TeamParameter();
    property.setKey("dylan.new.key");
    property.setToken("Dylan's New Value");

    TeamParameter newConfig =
        controller.createNewTeamProperty("5d1a1841f6ca2c00014c4309", property);
    TeamParameter newConfig2 =
        controller.createNewTeamProperty("5d1a1841f6ca2c00014c4302", property);

    Assertions.assertEquals("dylan.new.key", newConfig.getKey());
    Assertions.assertEquals("Dylan's New Value", newConfig.getToken());

    Assertions.assertEquals("dylan.new.key", newConfig2.getKey());
    Assertions.assertEquals("Dylan's New Value", newConfig2.getToken());
  }
  
  @Test
  public void testCreateNewTeamPropertyPassword() {
    TeamParameter property = new TeamParameter();
    property.setKey("dylan.new.key");
    property.setToken("Sensitive data");
    property.setLabel("password");

    TeamParameter newConfig =
        controller.createNewTeamProperty("5d1a1841f6ca2c00014c4309", property);
    
    Assertions.assertNull(newConfig.getToken());
   }

  @Test
  public void testGetTeamQuotas() {
    CurrentQuotas quotas = controller.getTeamQuotas("5d1a1841f6ca2c00014c4309");
    Assertions.assertEquals(Integer.valueOf(15), quotas.getMaxWorkflowCount());
    Assertions.assertEquals(Integer.valueOf(150), quotas.getMaxWorkflowRunMonthly());
    Assertions.assertEquals(Integer.valueOf(10), quotas.getMaxWorkflowStorage());
    Assertions.assertEquals(Integer.valueOf(60), quotas.getMaxWorkflowRunDuration());
    Assertions.assertEquals(Integer.valueOf(2), quotas.getMaxConcurrentRuns());

    Assertions.assertEquals(Integer.valueOf(9), quotas.getCurrentWorkflowCount());
    Assertions.assertEquals(Integer.valueOf(3), quotas.getCurrentConcurrentRuns());
    Assertions.assertEquals(Integer.valueOf(0), quotas.getCurrentRunTotalDuration());
    Assertions.assertEquals(Integer.valueOf(0), quotas.getCurrentTotalWorkflowStorage());
    Assertions.assertEquals(firstOfNextMonth(), quotas.getMonthlyResetDate());

    Assertions.assertEquals("5d1a1841f6ca2c00014c4309", controller.getTeams().get(0).getId());
    Assertions.assertEquals(quotas.getMaxWorkflowCount(),
        controller.getTeams().get(0).getQuotas().getMaxWorkflowCount());
    Assertions.assertEquals(quotas.getMaxConcurrentRuns(),
        controller.getTeams().get(0).getQuotas().getMaxConcurrentRuns());
    Assertions.assertEquals(quotas.getMaxWorkflowRunMonthly(),
        controller.getTeams().get(0).getQuotas().getMaxWorkflowRunMonthly());
    Assertions.assertEquals(quotas.getMaxWorkflowRunDuration(),
        controller.getTeams().get(0).getQuotas().getMaxWorkflowRunDuration());
    Assertions.assertEquals(quotas.getMaxWorkflowStorage(),
        controller.getTeams().get(0).getQuotas().getMaxWorkflowStorage());

  }

  @Test
  public void testResetTeamQuotas() {
    CurrentQuotas previousQuotas = controller.getTeamQuotas("5d1a1841f6ca2c00014c4309");

    Assertions.assertEquals(Integer.valueOf(15), previousQuotas.getMaxWorkflowCount());
    Assertions.assertEquals(Integer.valueOf(150), previousQuotas.getMaxWorkflowRunMonthly());
    Assertions.assertEquals(Integer.valueOf(10), previousQuotas.getMaxWorkflowStorage());
    Assertions.assertEquals(Integer.valueOf(60), previousQuotas.getMaxWorkflowRunDuration());
    Assertions.assertEquals(Integer.valueOf(2), previousQuotas.getMaxConcurrentRuns());

    controller.resetTeamQuotas("5d1a1841f6ca2c00014c4309");

    CurrentQuotas updatedQuotas = controller.getTeamQuotas("5d1a1841f6ca2c00014c4309");

    Assertions.assertEquals(Integer.valueOf(10), updatedQuotas.getMaxWorkflowCount());
    Assertions.assertEquals(Integer.valueOf(100), updatedQuotas.getMaxWorkflowRunMonthly());
    Assertions.assertEquals(Integer.valueOf(5), updatedQuotas.getMaxWorkflowStorage());
    Assertions.assertEquals(Integer.valueOf(30), updatedQuotas.getMaxWorkflowRunDuration());
    Assertions.assertEquals(Integer.valueOf(4), updatedQuotas.getMaxConcurrentRuns());

    Assertions.assertEquals(Integer.valueOf(9), updatedQuotas.getCurrentWorkflowCount());
    Assertions.assertEquals(Integer.valueOf(3), updatedQuotas.getCurrentConcurrentRuns());
    Assertions.assertEquals(Integer.valueOf(0), updatedQuotas.getCurrentRunTotalDuration());
    Assertions.assertEquals(Integer.valueOf(0),
        updatedQuotas.getCurrentTotalWorkflowStorage());
    Assertions.assertEquals(firstOfNextMonth(), updatedQuotas.getMonthlyResetDate());
  }

  @Test
  public void testUpdateQuotas() {
    CurrentQuotas current = controller.getTeamQuotas("5d1a1841f6ca2c00014c4303"); // team3.json
    Assertions.assertEquals(Integer.valueOf(10), current.getMaxWorkflowCount());
    Assertions.assertEquals(Integer.valueOf(4), current.getMaxConcurrentRuns());
    Assertions.assertEquals(Integer.valueOf(100), current.getMaxWorkflowRunMonthly());
    Assertions.assertEquals(Integer.valueOf(30), current.getMaxWorkflowRunDuration());
    Assertions.assertEquals(Integer.valueOf(5), current.getMaxWorkflowStorage());

    Quotas quotas = new Quotas();
    quotas.setMaxWorkflowCount(20);
    quotas.setMaxConcurrentRuns(8);
    quotas.setMaxWorkflowRunMonthly(200);
    quotas.setMaxWorkflowRunDuration(60);
    quotas.setMaxWorkflowStorage(10);

    Quotas updateQuotas = controller.updateTeamQuotas("5d1a1841f6ca2c00014c4303", quotas);
    Assertions.assertEquals(Integer.valueOf(20), updateQuotas.getMaxWorkflowCount());
    Assertions.assertEquals(Integer.valueOf(8), updateQuotas.getMaxConcurrentRuns());
    Assertions.assertEquals(Integer.valueOf(200), updateQuotas.getMaxWorkflowRunMonthly());
    Assertions.assertEquals(Integer.valueOf(60), updateQuotas.getMaxWorkflowRunDuration());
    Assertions.assertEquals(Integer.valueOf(10), updateQuotas.getMaxWorkflowStorage());

    CurrentQuotas updated = controller.getTeamQuotas("5d1a1841f6ca2c00014c4303");
    Assertions.assertEquals(updateQuotas.getMaxWorkflowCount(), updated.getMaxWorkflowCount());
    Assertions.assertEquals(updateQuotas.getMaxConcurrentRuns(),
        updated.getMaxConcurrentRuns());
    Assertions.assertEquals(updateQuotas.getMaxWorkflowRunMonthly(),
        updated.getMaxWorkflowRunMonthly());
    Assertions.assertEquals(updateQuotas.getMaxWorkflowRunDuration(),
        updated.getMaxWorkflowRunDuration());
    Assertions.assertEquals(updateQuotas.getMaxWorkflowStorage(), updated.getMaxWorkflowStorage());
  }

  @Test
  public void testUpdateQuotasForTeam() {
    Quotas quotas = new Quotas();
    quotas.setMaxWorkflowCount(25);
    quotas.setMaxConcurrentRuns(11);
    quotas.setMaxWorkflowRunMonthly(250);
    quotas.setMaxWorkflowRunDuration(74);
    quotas.setMaxWorkflowStorage(8);

    Quotas newQuotas = controller.updateQuotasForTeam("5d1a1841f6ca2c00014c4302", quotas);

    Assertions.assertEquals(Integer.valueOf(25), newQuotas.getMaxWorkflowCount());
    Assertions.assertEquals(Integer.valueOf(11), newQuotas.getMaxConcurrentRuns());
    Assertions.assertEquals(Integer.valueOf(250), newQuotas.getMaxWorkflowRunMonthly());
    Assertions.assertEquals(Integer.valueOf(74), newQuotas.getMaxWorkflowRunDuration());
    Assertions.assertEquals(Integer.valueOf(8), newQuotas.getMaxWorkflowStorage());

    CurrentQuotas updated = controller.getTeamQuotas("5d1a1841f6ca2c00014c4302");
    Assertions.assertEquals(newQuotas.getMaxWorkflowCount(), updated.getMaxWorkflowCount());
    Assertions.assertEquals(newQuotas.getMaxConcurrentRuns(),
        updated.getMaxConcurrentRuns());
    Assertions.assertEquals(newQuotas.getMaxWorkflowRunMonthly(),
        updated.getMaxWorkflowRunMonthly());
    Assertions.assertEquals(newQuotas.getMaxWorkflowRunDuration(),
        updated.getMaxWorkflowRunDuration());
    Assertions.assertEquals(newQuotas.getMaxWorkflowStorage(), updated.getMaxWorkflowStorage());
  }

  @Test
  public void testGetDefaultQuotas() {
    Quotas quota = controller.getDefaultQuotas();
    Assertions.assertEquals(Integer.valueOf(4), quota.getMaxConcurrentRuns());
    Assertions.assertEquals(Integer.valueOf(10), quota.getMaxWorkflowCount());
    Assertions.assertEquals(Integer.valueOf(100), quota.getMaxWorkflowRunMonthly());
    Assertions.assertEquals(Integer.valueOf(30), quota.getMaxWorkflowRunDuration());
    Assertions.assertEquals(Integer.valueOf(5), quota.getMaxWorkflowStorage());
  }

  @Test
  public void deactivateTeam() {
    TeamEntity entity = controller.deactivateTeam("5d1a1841f6ca2c00014c4309");
    Assertions.assertEquals(false, entity.getIsActive());
    Assertions.assertEquals(2, controller.getTeams().size());
  }
  

  @Test
  public void testInternalTeamWorkflows() {
    List<WorkflowSummary> summaryList = controller.getTeamWorkflows("5d1a1841f6ca2c00014c4309");

     assertNotNull(summaryList);
     assertEquals(9, summaryList.size());

  }

  private Date firstOfNextMonth() {
    Calendar nextMonth = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    nextMonth.add(Calendar.MONTH, 1);
    nextMonth.set(Calendar.DAY_OF_MONTH, 1);
    nextMonth.set(Calendar.HOUR_OF_DAY, 0);
    nextMonth.set(Calendar.MINUTE, 0);
    nextMonth.set(Calendar.SECOND, 0);
    nextMonth.set(Calendar.MILLISECOND, 0);
    return nextMonth.getTime();
  }
}
