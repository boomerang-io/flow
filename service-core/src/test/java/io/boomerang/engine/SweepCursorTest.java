package io.boomerang.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;

/**
 * A sweep cursor reaches every candidate: with more than a page, successive pages walk all of them
 * and wrap; with fewer, every page holds every candidate, as an oldest-first page did.
 */
class SweepCursorTest extends AbstractEngineIntegrationTest {

  private static final String COLLECTION = "sweep_cursor_test";
  private static final int PAGE = 50;

  @Autowired private MongoTemplate mongoTemplate;

  @AfterEach
  void dropTheRows() {
    mongoTemplate.dropCollection(COLLECTION);
  }

  @Test
  void morePagesOfCandidatesThanOneAreAllReachedAndWrap() {
    Set<String> all = rows(120);
    SweepCursor cursor = new SweepCursor("at");

    Set<String> seen = new HashSet<>();
    for (int read = 0; read < 3; read++) {
      List<Document> page = read(cursor);
      assertThat(page).hasSize(PAGE);
      page.forEach(row -> seen.add(row.getObjectId("_id").toHexString()));
    }

    assertThat(seen).as("three pages of 50 reach all 120, the third wrapping").isEqualTo(all);
  }

  @Test
  void fewerCandidatesThanAPageAreAllReadEveryTime() {
    Set<String> all = rows(30);
    SweepCursor cursor = new SweepCursor("at");

    for (int read = 0; read < 3; read++) {
      Set<String> page = new HashSet<>();
      read(cursor).forEach(row -> page.add(row.getObjectId("_id").toHexString()));
      assertThat(page).isEqualTo(all);
    }
  }

  private List<Document> read(SweepCursor cursor) {
    return cursor.read(
        Criteria.where("at").exists(true),
        PAGE,
        query -> mongoTemplate.find(query, Document.class, COLLECTION),
        row -> row.getDate("at"),
        row -> row.getObjectId("_id").toHexString());
  }

  // Rows one second apart, oldest first.
  private Set<String> rows(int count) {
    long base = System.currentTimeMillis() - count * 1000L;
    List<Document> rows = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      rows.add(new Document("at", new Date(base + i * 1000L)));
    }
    mongoTemplate.getCollection(COLLECTION).insertMany(rows);
    Set<String> ids = new HashSet<>();
    rows.forEach(row -> ids.add(row.getObjectId("_id").toHexString()));
    return ids;
  }
}
