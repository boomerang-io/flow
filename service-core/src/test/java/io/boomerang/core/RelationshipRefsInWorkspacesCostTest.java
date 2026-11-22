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
            nodeRepository, edgeRepository, identityService, new SimpleMeterRegistry(), template);
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

    long workflowsFound = (long) WORKSPACES * WORKFLOWS;
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
    // The memberships and each workspace's edges to a workflow, once: no edge of another label
    // is read, and none is read again to place a workflow under its workspace.
    assertThat(recorded.documentsRead("rel_edges")).isEqualTo(WORKSPACES + workflowsFound);
    // The member, the workspaces named (twice: as named and as the member's), and the workflows;
    // no run, and no child of another kind.
    assertThat(recorded.documentsRead("rel_nodes")).isEqualTo(1 + 2L * WORKSPACES + workflowsFound);
    // Six queries, the larger two continued by a getMore each: the workspaces as named, the
    // member, the member's edges, the member's workspaces, their workflow edges and the workflows.
    // The workflows are the target, so nothing is asked of them.
    assertThat(recorded.names().stream().filter(name -> name.startsWith("find "))).hasSize(6);
    // Nodes and edges are read as documents, never mapped to entities: about 2.6 KB per workflow
    // found, where mapping each to an entity took about 6.7 KB.
    assertThat(allocated).isLessThan(WORKSPACES * WORKFLOWS * 4_000L);
  }

  @Test
  void listingApproverGroupsReadsNoWorkflowEdgeAndNoWorkflow() {
    Supplier<Map<String, List<String>>> listing =
        () -> service.refsInWorkspaces(RelationshipType.APPROVERGROUP, workspaces);

    Recorded<Map<String, List<String>>> recorded = inRequest(() -> DatabaseCommands.record(listing));
    long groups = (long) WORKSPACES * (OTHER_CHILDREN / 3);
    assertThat(recorded.result()).hasSize(WORKSPACES);
    assertThat(recorded.result().values().stream().mapToLong(List::size).sum()).isEqualTo(groups);
    System.out.printf(
        "REL COST | approver groups of %d workspaces | %s | %d edges | %d nodes | %d reply bytes%n",
        WORKSPACES,
        recorded.names(),
        recorded.documentsRead("rel_edges"),
        recorded.documentsRead("rel_nodes"),
        recorded.replyBytes());
    // The memberships and each workspace's approver-group edges: none of its 50 workflow edges.
    assertThat(recorded.documentsRead("rel_edges")).isEqualTo(WORKSPACES + groups);
    assertThat(recorded.documentsRead("rel_nodes")).isEqualTo(1 + 2L * WORKSPACES + groups);
  }

  @Test
  void countingTheWorkflowsOfAHundredWorkspacesReadsNoWorkflow() {
    Supplier<Map<String, Long>> counting = () -> service.workflowCountsInWorkspaces(workspaces);

    Recorded<Map<String, Long>> recorded = inRequest(() -> DatabaseCommands.record(counting));
    assertThat(recorded.result()).hasSize(WORKSPACES);
    assertThat(recorded.result().values()).containsOnly((long) WORKFLOWS);
    long allocated = allocatedPerCall(counting);
    System.out.printf(
        "REL COST | counting %d workspaces | %s | %d edges | %d nodes | %d reply bytes"
            + " | %d bytes allocated per call%n",
        WORKSPACES,
        recorded.names(),
        recorded.documentsRead("rel_edges"),
        recorded.documentsRead("rel_nodes"),
        recorded.replyBytes(),
        allocated);
    // The member and the workspaces named, as named and as the member's: no workflow node.
    assertThat(recorded.documentsRead("rel_nodes")).isEqualTo(1 + 2L * WORKSPACES);
    // The memberships: the aggregation returns a count for each workspace and no workflow edge.
    assertThat(recorded.documentsRead("rel_edges")).isEqualTo(WORKSPACES);
    assertThat(recorded.names().stream().filter(name -> name.startsWith("aggregate "))).hasSize(1);
    assertThat(recorded.names().stream().filter(name -> name.startsWith("find "))).hasSize(4);
    // In proportion to the workspaces, not to their workflows: about 14 KB for each, the
    // workspace's node and the membership read whole.
    assertThat(allocated).isLessThan(WORKSPACES * 25_000L);
  }

  /**
   * One request reads the caller's memberships once, however many lookups it makes: the roles
   * authentication resolves, a workspace check, a workflow listing and the profile's counts.
   */
  @Test
  void aRequestReadsTheCallersMembershipsOnce() {
    Recorded<Boolean> recorded =
        inRequest(
            () ->
                DatabaseCommands.record(
                    () -> {
                      assertThat(service.roles("member")).hasSize(WORKSPACES);
                      assertThat(
                              service.refsInWorkspace(
                                  RelationshipType.WORKFLOW, workspaces.get(1), Optional.empty()))
                          .hasSize(WORKFLOWS);
                      assertThat(service.workflowCountsInWorkspaces(workspaces)).hasSize(WORKSPACES);
                      assertThat(service.roles("member")).hasSize(WORKSPACES);
                      return service.check(
                          RelationshipType.WORKSPACE, workspaces.get(0), Optional.empty(),
                          Optional.empty());
                    }));
    assertThat(recorded.result()).isTrue();
    // The hundred memberships once, and one workspace's fifty workflow edges.
    assertThat(recorded.documentsRead("rel_edges")).isEqualTo(WORKSPACES + WORKFLOWS);
  }

  /** The two reads of a walk level are answered by the from_label index and the _id index. */
  @Test
  void aWalkLevelsReadsUseTheFromLabelAndIdIndexes() {
    String edges = template.getCollectionName(RelationshipEdgeEntity.class);
    String nodes = template.getCollectionName(RelationshipNodeEntity.class);
    List<String> workspaceIds = new ArrayList<>();
    template
        .getCollection(nodes)
        .find(new Document("type", "workspace"))
        .forEach(node -> workspaceIds.add(node.getString("_id")));

    Document level =
        explain(
            edges,
            new Document("from", new Document("$in", workspaceIds))
                .append("label", new Document("$in", List.of("hasWorkflow", "linksTemplate"))),
            new Document("from", 1).append("to", 1).append("_id", 0));
    assertThat(level.get("queryPlanner", Document.class).toJson()).contains("\"from_label\"");
    Document levelStats = level.get("executionStats", Document.class);
    // Only the workflow edges are fetched: no run edge and no edge of another label.
    assertThat(levelStats.get("nReturned")).isEqualTo(WORKSPACES * WORKFLOWS);
    assertThat(levelStats.get("totalDocsExamined")).isEqualTo(levelStats.get("nReturned"));

    List<String> workflowIds = new ArrayList<>();
    workflows.values().forEach(refs -> refs.forEach(ref -> workflowIds.add("workflow:" + ref)));
    Document children =
        explain(
            nodes,
            new Document("_id", new Document("$in", workflowIds))
                .append(
                    "$or",
                    List.of(
                        new Document("type", new Document("$ne", "workflow")),
                        new Document("slug", new Document("$in", List.of("wf-3"))),
                        new Document("ref", new Document("$in", List.of("wf-3"))))),
            new Document("type", 1).append("ref", 1).append("slug", 1));
    assertThat(children.get("queryPlanner", Document.class).toJson())
        .contains("\"_id_\"")
        .doesNotContain("COLLSCAN");
    assertThat(children.get("executionStats", Document.class).get("nReturned")).isEqualTo(WORKSPACES);

    Document counts =
        template
            .getDb()
            .runCommand(
                new Document(
                        "explain",
                        new Document("aggregate", edges)
                            .append(
                                "pipeline",
                                List.of(
                                    new Document(
                                        "$match",
                                        new Document("from", new Document("$in", workspaceIds))
                                            .append("label", "hasWorkflow")),
                                    new Document(
                                        "$group",
                                        new Document("_id", "$from")
                                            .append("n", new Document("$sum", 1)))))
                            .append("cursor", new Document()))
                    .append("verbosity", "queryPlanner"));
    assertThat(counts.toJson()).contains("\"from_label\"").doesNotContain("COLLSCAN");
  }

  private Document explain(String collection, Document filter, Document projection) {
    return template
        .getDb()
        .runCommand(
            new Document(
                    "explain",
                    new Document("find", collection)
                        .append("filter", filter)
                        .append("projection", projection))
                .append("verbosity", "executionStats"));
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
    // The member, the workspace, then the workflow named: none of the workspace's other workflows
    // and none of its six other children, whose edges are not read either.
    assertThat(recorded.documentsRead("rel_nodes")).isEqualTo(1 + 1 + 1);
    assertThat(recorded.documentsRead("rel_edges")).isEqualTo(WORKSPACES + WORKFLOWS);
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
