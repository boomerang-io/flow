package io.boomerang.loader.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads the bootstrap seed documents shipped under {@code src/main/resources/seed} and inserts
 * them only where they are absent.
 *
 * <p>Each seed file is a single JSON object wrapping the collection's documents in a {@code
 * documents} array — one file per collection rather than a file per document, because a classpath
 * directory cannot be listed from inside a jar without a hand-maintained manifest. The content is
 * MongoDB relaxed Extended JSON ({@code {"$oid": ...}}, {@code {"$date": ...}}) as produced by
 * {@code mongoexport}, which {@link Document#parse} reads natively.
 *
 * <p>The documents are the *final* state of the legacy {@code boomerangio/flow-loader} image
 * (captured by running it against an empty database), normalised to the v5 entity shapes. Field
 * names are the Mongo/Spring-Data ones, not the Jackson API ones — for example {@code
 * AbstractParam.defaultValue} is stored as {@code defaultValue}, and map keys keep the legacy
 * {@code #}-for-{@code .} escaping that {@code MongoConfiguration.setMapKeyDotReplacement("#")}
 * still applies. {@code _class} is omitted throughout: Spring Data falls back to the declared
 * entity type when the discriminator is absent.
 *
 * <p>Documents whose legacy {@code _id} was generated at migration time (roles, integration
 * templates, the system workspace, task revisions) carry a deterministic {@code 5eed}-prefixed
 * ObjectId instead, so a re-run produces byte-identical documents. Ids that were literals in the
 * legacy resources (tasks, settings, workflow templates) are preserved verbatim, so an upgraded v4
 * install matches on them and is skipped.
 */
public abstract class SeedResources {

  private static final Logger LOG = LoggerFactory.getLogger(SeedResources.class);

  private SeedResources() {}

  /** Read a seed file's {@code documents} array from the classpath. */
  public static List<Document> load(String resource) {
    try (InputStream stream =
        SeedResources.class.getClassLoader().getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IllegalStateException("Seed resource not found on the classpath: " + resource);
      }
      Document wrapper = Document.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
      List<Document> documents = wrapper.getList("documents", Document.class);
      if (documents == null) {
        throw new IllegalStateException("Seed resource has no 'documents' array: " + resource);
      }
      return documents;
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read seed resource " + resource, e);
    }
  }

  /**
   * Insert {@code document} unless {@code filter} already matches — the natural-key guard that
   * makes every seed change unit idempotent and non-destructive: an existing document (a v4
   * install's own copy, or this change unit's own earlier run) is left exactly as it is.
   *
   * @return true if the document was inserted
   */
  public static boolean insertIfAbsent(
      MongoDatabase db, String collection, Bson filter, Document document) {
    MongoCollection<Document> coll = db.getCollection(collection);
    if (coll.find(filter).first() != null) {
      return false;
    }
    coll.insertOne(document);
    return true;
  }

  /** A {@code rel_nodes} document in the shape {@code RelationshipNodeEntity} writes. */
  public static Document node(String type, String ref, String slug) {
    return new Document("_id", type + ":" + ref)
        .append("creationDate", new Date())
        .append("type", type)
        .append("ref", ref)
        .append("slug", slug)
        .append("data", new Document());
  }

  /** A {@code rel_edges} document in the shape {@code RelationshipEdgeEntity} writes. */
  public static Document edge(String from, String label, String to, Document data) {
    return new Document("creationDate", new Date())
        .append("from", from)
        .append("label", label)
        .append("to", to)
        .append("data", data);
  }

  public static void logSeeded(String what, int inserted, int total) {
    LOG.info("Seeded {} — {} inserted, {} already present", what, inserted, total - inserted);
  }
}
