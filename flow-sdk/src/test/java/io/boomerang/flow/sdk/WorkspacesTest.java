package io.boomerang.flow.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.boomerang.flow.sdk.model.Workspace;
import io.boomerang.flow.sdk.model.WorkspaceStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/** Each workspace route's method, path, query and body, and the ensure-by-reference rule. */
class WorkspacesTest {

  private static final String WORKSPACES = StubServer.API + "/workspace";
  private static final String MIRROR =
      "{\"id\":\"w1\",\"name\":\"archie-6ae2e9cc99b43262aa3e8e28\",\"displayName\":\"Acme\","
          + "\"externalRef\":\"6ae2e9cc99b43262aa3e8e28\",\"status\":\"active\",\"type\":\"pro\","
          + "\"creationDate\":\"2026-10-01T10:00:00.000+00:00\",\"labels\":{\"tenant\":\"acme\"},"
          + "\"members\":[],\"quotas\":{\"maxWorkflowCount\":0}}";

  private final StubServer stub = new StubServer();
  private final Workspaces workspaces = stub.flow.workspaces();

  @AfterEach
  void verify() {
    stub.server.verify();
  }

  @Test
  void createPostsTheRequestAndReadsTheWorkspace() {
    stub.server
        .expect(requestTo(WORKSPACES))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer secret"))
        .andExpect(
            StubServer.jsonBody(
                "{\"name\":\"archie-6ae2e9cc99b43262aa3e8e28\",\"displayName\":\"Acme\","
                    + "\"externalRef\":\"6ae2e9cc99b43262aa3e8e28\",\"labels\":{\"tenant\":\"acme\"}}"))
        .andRespond(withSuccess(MIRROR, MediaType.APPLICATION_JSON));

    Workspace created =
        workspaces.create(
            WorkspaceRequest.of("archie-6ae2e9cc99b43262aa3e8e28", "Acme")
                .externalRef("6ae2e9cc99b43262aa3e8e28")
                .label("tenant", "acme"));

    assertThat(created.name()).isEqualTo("archie-6ae2e9cc99b43262aa3e8e28");
    assertThat(created.externalRef()).isEqualTo("6ae2e9cc99b43262aa3e8e28");
    assertThat(created.status()).isEqualTo(WorkspaceStatus.active);
    assertThat(created.labels()).containsEntry("tenant", "acme");
  }

  @Test
  void ensureReadsTheWorkspaceThatAlreadyCarriesTheReference() {
    stub.server
        .expect(requestTo(WORKSPACES))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withStatus(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    "{\"code\":1105,\"reason\":\"WORKSPACE_EXTERNAL_REF_TAKEN\","
                        + "\"message\":\"A workspace already carries this external reference:"
                        + " \\\"archie-6ae2e9cc99b43262aa3e8e28\\\".\",\"status\":\"409 CONFLICT\"}"));
    stub.server
        .expect(
            requestTo(WORKSPACES + "/query?externalRef=6ae2e9cc99b43262aa3e8e28&limit=1&page=0"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(StubServer.page(0, 1, 1, true, MIRROR), MediaType.APPLICATION_JSON));

    Workspace ensured =
        workspaces.ensure(
            WorkspaceRequest.of("archie-6ae2e9cc99b43262aa3e8e28", "Acme")
                .externalRef("6ae2e9cc99b43262aa3e8e28"));

    assertThat(ensured.id()).isEqualTo("w1");
    assertThatThrownBy(() -> workspaces.ensure(WorkspaceRequest.of("x", "X")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void ensureRethrowsAnyOtherRefusal() {
    stub.server
        .expect(requestTo(WORKSPACES))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    "{\"code\":1102,\"reason\":\"WORKSPACE_NON_UNIQUE_NAME\","
                        + "\"message\":\"taken\",\"status\":\"422 UNPROCESSABLE_ENTITY\"}"));

    assertThatThrownBy(() -> workspaces.ensure(WorkspaceRequest.of("x", "X").externalRef("e")))
        .isInstanceOf(FlowApiException.class)
        .extracting(e -> ((FlowApiException) e).reason())
        .isEqualTo("WORKSPACE_NON_UNIQUE_NAME");
  }

  @Test
  void getQueryAndDeleteUseTheirRoutes() {
    stub.server
        .expect(requestTo(WORKSPACES + "/archie-6ae2e9cc99b43262aa3e8e28"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(MIRROR, MediaType.APPLICATION_JSON));
    stub.server
        .expect(
            requestTo(
                WORKSPACES
                    + "/query?search=ac&labels=tenant%3Dacme&statuses=active&workspaces=archie-6ae2e9cc99b43262aa3e8e28"
                    + "&externalRef=6ae2e9cc99b43262aa3e8e28&limit=20&page=0&order=ASC&sort=name"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(StubServer.page(0, 20, 1, true, MIRROR), MediaType.APPLICATION_JSON));
    stub.server
        .expect(requestTo(WORKSPACES + "/archie-6ae2e9cc99b43262aa3e8e28"))
        .andExpect(method(HttpMethod.DELETE))
        .andRespond(withSuccess());

    assertThat(workspaces.get("archie-6ae2e9cc99b43262aa3e8e28").displayName()).isEqualTo("Acme");
    assertThat(
            workspaces
                .query(
                    WorkspaceQuery.all()
                        .search("ac")
                        .labels("tenant=acme")
                        .statuses(WorkspaceStatus.active)
                        .names("archie-6ae2e9cc99b43262aa3e8e28")
                        .externalRef("6ae2e9cc99b43262aa3e8e28")
                        .page(0, 20)
                        .sort("name", Order.ASC))
                .content())
        .extracting(Workspace::id)
        .containsExactly("w1");
    workspaces.delete("archie-6ae2e9cc99b43262aa3e8e28");
  }
}
