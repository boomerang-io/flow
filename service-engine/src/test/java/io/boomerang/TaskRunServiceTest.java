import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.model.TaskRun;
import io.boomerang.engine.TaskRunService;

class TaskRunServiceTest {

  @Mock private MongoTemplate mongoTemplate;

  @InjectMocks private TaskRunService taskRunService;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
  }

  @DisplayName("Test query method with different input parameters")
  @ParameterizedTest(
      name = "from={0}, to={1}, pageSize={2}, pageNumber={3}, sort={4}")
  @CsvSource({
    "0, 9223372036854775807, 10, 0, ASC",
    "0, 9223372036854775807, 20, 1, DESC",
    "0, 9223372036854775807, 30, 2, ASC"
  })
  void testQuery(
      long from,
      long to,
      int pageSize,
      int pageNumber,
      String sort) {
    List<TaskRunEntity> taskRunEntities = List.of(new TaskRunEntity(), new TaskRunEntity());
    when(mongoTemplate.find(any(Query.class), eq(TaskRunEntity.class))).thenReturn(taskRunEntities);

    Optional<Date> fromDate = Optional.of(new Date(from));
    Optional<Date> toDate = Optional.of(new Date(to));
    Optional<Direction> sortDirection = Optional.of(Direction.fromString(sort));
    Page<TaskRun> result =
        taskRunService.query(
            fromDate,
            toDate,
            Optional.of(pageSize),
            Optional.of(pageNumber),
            sortDirection,
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    assertAll(
        () -> assertEquals(taskRunEntities.size(), result.getContent().size()),
        () -> assertNotNull(result.getContent().get(0)),
        () -> assertNotNull(result.getContent().get(1)));
  }
}
