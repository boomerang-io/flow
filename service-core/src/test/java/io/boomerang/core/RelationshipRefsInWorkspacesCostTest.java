package io.boomerang.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import io.boomerang.core.config.MongoConfiguration;
import io.boomerang.core.entity.RelationshipEdgeEntity;
import io.boomerang.core.entity.RelationshipNodeEntity;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.Token;
import io.boomerang.core.repository.RelationshipEdgeRepository;
import io.boomerang.core.repository.RelationshipNodeRepository;
import io.boomerang.core.security.IdentityService;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.boomerang.engine.DatabaseCommands;
import io.boomerang.engine.DatabaseCommands.Recorded;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * What a member of many workspaces costs when their workflows are listed across all of them: 100
 * workspaces of 50 workflows, six other children and 20 runs each. The walk returns the workflow
 * nodes and the edges it follows, each read once, with no data and no run; a lookup by name reads
 * only the workflow it names; and the heap allocated per call stays in proportion to what is found.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = RelationshipRefsInWorkspacesCostTest.CostConfig.class)
@TestPropertySource(properties = "flow.mongo.collection.prefix=flowcost")
class RelationshipRefsInWorkspacesCostTest {

  private static final int WORKSPACES = 100;
  private static final int WORKFLOWS = 50;
  private static final int OTHER_CHILDREN = 6;
  private static final int RUNS = 20;

  private static final MongoDBContainer MONGO =
      new MongoDBContainer(DockerImageName.parse("mongo:7.0"));

  static {
    MONGO.start();
  }

  @Configuration
  @EnableMongoRepositories(
      basePackageClasses = RelationshipNodeRepository.class,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = {RelationshipNodeRepository.class, RelationshipEdgeRepository.class}))
  static class CostConfig {
    @Bean
    static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
      return new PropertySourcesPlaceholderConfigurer();
    }

    @Bean
    MongoConfiguration mongoConfiguration() {
      return new MongoConfiguration();
    }

    @Bean
    MongoClient mongoClient() {
      return MongoClients.create(
          MongoClientSettings.builder()
              .applyConnectionString(new ConnectionString(MONGO.getReplicaSetUrl("boomerang")))
              .addCommandListener(DatabaseCommands.listener())
              .build());
    }

    @Bean
    MongoDatabaseFactory mongoDatabaseFactory(MongoClient client) {
      return new SimpleMongoClientDatabaseFactory(client, "boomerang");
    }

    @Bean
    MongoMappingContext mongoMappingContext() {
      return new MongoMappingContext();
    }

    @Bean
    MappingMongoConverter mappingMongoConverter(
        MongoDatabaseFactory mongoDatabaseFactory, MongoMappingContext mongoMappingContext) {
      return new MappingMongoConverter(
          new DefaultDbRefResolver(mongoDatabaseFactory), mongoMappingContext);
    }

    @Bean
    MongoTemplate mongoTemplate(
        MongoDatabaseFactory mongoDatabaseFactory, MappingMongoConverter mappingMongoConverter) {
      return new MongoTemplate(mongoDatabaseFactory, mappingMongoConverter);
    }
  }

  @Autowired private RelationshipNodeRepository nodeRepository;
  @Autowired private RelationshipEdgeRepository edgeRepository;
  @Autowired private MongoTemplate template;

  private RelationshipService service;
  private final List<String> workspaces = new ArrayList<>();
  private final Map<String, List<String>> workflows = new HashMap<>();

  @BeforeEach
  void aMemberOfAHundredWorkspaces() {
    IdentityService identityService = mock(IdentityService.class);
    service =
        new RelationshipService(
            nodeRepository, edgeRepository, identityService, new SimpleMeterRegistry());
    Token token = new Token(AuthScope.session);
    token.setPrincipal("member");
    token.setPermissions(
        List.of(new ResolvedPermissions(PermissionScope.workspace, "ws-0", List.of("**/**"))));
    when(identityService.getCurrentIdentity()).thenReturn(token);
    seed();
  }

  @Test
  void listingTheWorkflowsOfAHundredWorkspacesReadsEachOnceAndAllocatesInProportion() {
    Supplier<Map<String, List<String>>> listing =
        () -> service.refsInWorkspaces(RelationshipType.WORKFLOW, workspaces);

    Recorded<Map<String, List<String>>> recorded = inRequest(() -> DatabaseCommands.record(listing));
    assertThat(recorded.result()).hasSize(WORKSPACES);
    workflows.forEach(
        (workspace, refs) ->
            assertThat(recorded.result().get(workspace))
                .as(workspace)
                .containsExactlyInAnyOrderElementsOf(refs));

    long children = (long) WORKSPACES * (WORKFLOWS + OTHER_CHILDREN);
    long allocated = allocatedPerCall(listing);
    System.out.printf(
        "REL COST | listing %d workspaces | %s | %d edges | %d nodes | %d reply bytes"
            + " | %d bytes allocated per call%n",
        WORKSPACES,
        recorded.names(),
        recorded.documentsRead("rel_edges"),
        recorded.documentsRead("rel_nodes"),
        recorded.replyBytes(),
        allocated);
    // The memberships and each workspace's non-run edges, once: no edge is read again to place a
    // workflow under its workspace.
    assertThat(recorded.documentsRead("rel_edges")).isEqualTo(WORKSPACES + children);
    // The member, the workspaces named (twice: as named and as the member's), and their non-run
    // children; no run.
    assertThat(recorded.documentsRead("rel_nodes")).isEqualTo(1 + 2L * WORKSPACES + children);
    // Seven queries, the larger two continued by a getMore each: the workspaces as named, the
    // member, the member's edges, the member's workspaces, their edges, their children, and the
    // children's own edges (none). Before, an eighth re-read every edge into the workflows found.
    assertThat(recorded.names().stream().filter(name -> name.startsWith("find "))).hasSize(7);
    // Nodes and edges come back as their ends, refs and names, each mapped once: about 6.7 KB per
    // workflow found, where re-reading the edges into whole nodes took about 13.6 KB.
    assertThat(allocated).isLessThan(WORKSPACES * WORKFLOWS * 9_000L);
  }

  @Test
  void aWorkflowLookupByNameReadsOnlyTheWorkflowItNames() {
    String workspace = workspaces.get(7);
    String name = "wf-3";
    Supplier<List<String>> lookup =
        () ->
            service.refsInWorkspace(RelationshipType.WORKFLOW, workspace, Optional.of(List.of(name)));

    Recorded<List<String>> recorded = inRequest(() -> DatabaseCommands.record(lookup));
    assertThat(recorded.result()).containsExactly(workflows.get(workspace).get(3));
    long allocated = allocatedPerCall(lookup);
    System.out.printf(
        "REL COST | workflow by name | %d commands | %d edges | %d nodes | %d reply bytes"
            + " | %d bytes allocated per call%n",
        recorded.commands().size(),
        recorded.documentsRead("rel_edges"),
        recorded.documentsRead("rel_nodes"),
        recorded.replyBytes(),
        allocated);
    // The member, the workspace, then the workflow named and the workspace's six other children:
    // none of its other workflows.
    assertThat(recorded.documentsRead("rel_nodes")).isEqualTo(1 + 1 + 1 + OTHER_CHILDREN);
    assertThat(recorded.documentsRead("rel_edges"))
        .isEqualTo(WORKSPACES + WORKFLOWS + OTHER_CHILDREN);
  }

  // The heap this thread allocates per call, over calls each in a request of its own.
  private static long allocatedPerCall(Supplier<?> call) {
    com.sun.management.ThreadMXBean threads =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    long thread = Thread.currentThread().threadId();
    for (int i = 0; i < 5; i++) {
      inRequest(call);
    }
    int calls = 10;
    long before = threads.getThreadAllocatedBytes(thread);
    for (int i = 0; i < calls; i++) {
      inRequest(call);
    }
    return (threads.getThreadAllocatedBytes(thread) - before) / calls;
  }

  private static <T> T inRequest(Supplier<T> call) {
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    try {
      return call.get();
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }
  }

  // ── Seed: straight into the collections, with the indexes the migrations build ─────────────

  private void seed() {
    MongoCollection<Document> nodes =
        template.getCollection(template.getCollectionName(RelationshipNodeEntity.class));
    MongoCollection<Document> edges =
        template.getCollection(template.getCollectionName(RelationshipEdgeEntity.class));
    nodes.drop();
    edges.drop();
    nodes.createIndex(
        new Document("type", -1).append("slug", -1), new IndexOptions().name("type_slug"));
    nodes.createIndex(
        new Document("type", -1).append("ref", -1), new IndexOptions().name("type_ref"));
    edges.createIndex(
        new Document("from", -1).append("label", -1), new IndexOptions().name("from_label"));
    edges.createIndex(
        new Document("to", -1).append("label", -1), new IndexOptions().name("to_label"));
    workspaces.clear();
    workflows.clear();

    List<Document> nodeDocs = new ArrayList<>();
    List<Document> edgeDocs = new ArrayList<>();
    nodeDocs.add(node("root", "root", "root"));
    nodeDocs.add(node("user", "member", "member@test.local"));
    edgeDocs.add(edge("root:root", "contains", "user:member", null));
    for (int w = 0; w < WORKSPACES; w++) {
      String ref = new ObjectId().toHexString();
      String slug = "ws-" + w;
      String workspaceNode = "workspace:" + ref;
      workspaces.add(slug);
      nodeDocs.add(node("workspace", ref, slug));
      edgeDocs.add(edge("root:root", "contains", workspaceNode, null));
      edgeDocs.add(edge("user:member", "memberOf", workspaceNode, "member"));
      List<String> refs = new ArrayList<>();
      for (int f = 0; f < WORKFLOWS; f++) {
        String workflow = new ObjectId().toHexString();
        refs.add(workflow);
        nodeDocs.add(node("workflow", workflow, "wf-" + f));
        edgeDocs.add(edge(workspaceNode, "hasWorkflow", "workflow:" + workflow, null));
      }
      workflows.put(slug, refs);
      for (String[] child :
          List.of(
              new String[] {"workspacetask", "hasTask"},
              new String[] {"approvergroup", "hasApproverGroup"},
              new String[] {"integration", "hasIntegration"})) {
        for (int c = 0; c < OTHER_CHILDREN / 3; c++) {
          String other = new ObjectId().toHexString();
          nodeDocs.add(node(child[0], other, child[0] + "-" + c));
          edgeDocs.add(edge(workspaceNode, child[1], child[0] + ":" + other, null));
        }
      }
      for (int r = 0; r < RUNS; r++) {
        String run = new ObjectId().toHexString();
        nodeDocs.add(node("workflowrun", run, run));
        edgeDocs.add(edge(workspaceNode, "hasWorkflowRun", "workflowrun:" + run, null));
      }
    }
    nodes.insertMany(nodeDocs);
    edges.insertMany(edgeDocs);
  }

  private static final Date NOW = new Date();

  private static Document node(String type, String ref, String slug) {
    return new Document("_id", type + ":" + ref)
        .append("creationDate", NOW)
        .append("type", type)
        .append("ref", ref)
        .append("slug", slug)
        .append("data", new Document())
        .append("_class", RelationshipNodeEntity.class.getName());
  }

  private static Document edge(String from, String label, String to, String role) {
    return new Document("_id", new ObjectId())
        .append("creationDate", NOW)
        .append("from", from)
        .append("label", label)
        .append("to", to)
        .append("data", (role != null) ? new Document("role", role) : new Document())
        .append("_class", RelationshipEdgeEntity.class.getName());
  }
}
