import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort.Direction;

import io.boomerang.engine.repository.TaskRunRepository;

class TaskRunServiceTest {

  @Mock private TaskRunRepository taskRunRepository;

  @InjectMocks private TaskRunService taskRunService;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
  }

  @DisplayName("Test query method with different input parameters")
  @ParameterizedTest(
      name =
          "from={0}, to={1}, pageSize={2}, pageNumber={3}, sort={4}, labels={5}, status={6}, phase={7}")
  @CsvSource({
    "0, 9223372036854775807, 10, 0, ASC, label, status, phase",
    "0, 9223372036854775807, 20, 1, DESC, label1, status1, phase1",
    "0, 9223372036854775807, 30, 2, ASC, label2, status2, phase2"
  })
  void testQuery(
      long from,
      long to,
      int pageSize,
      int pageNumber,
      String sort,
      String labels,
      String status,
      String phase) {
    List<TaskRun> taskRuns = new ArrayList<>();
    taskRuns.add(new TaskRun(new Date(), "label1", "status1", "phase1"));
    taskRuns.add(new TaskRun(new Date(), "label2", "status2", "phase2"));
    Page<TaskRun> page = new PageImpl<>(taskRuns);
    when(taskRunRepository
            .findByCreationDateBetweenAndLabelsContainingIgnoreCaseAndStatusContainingIgnoreCaseAndPhaseContainingIgnoreCase(
                new Date(from),
                new Date(to),
                labels,
                status,
                phase,
                PageRequest.of(pageNumber, pageSize, Direction.fromString(sort), "creationDate")))
        .thenReturn(page);

    Optional<Date> fromDate = Optional.of(new Date(from));
    Optional<Date> toDate = Optional.of(new Date(to));
    Optional<Direction> sortDirection = Optional.of(Direction.fromString(sort));
    Optional<String> label = Optional.of(labels);
    Optional<String> taskStatus = Optional.of(status);
    Optional<String> taskPhase = Optional.of(phase);
    Page<TaskRun> result =
        taskRunService.query(
            fromDate, toDate, pageSize, pageNumber, sortDirection, label, taskStatus, taskPhase);

    assertAll(
        () -> assertEquals(page, result),
        () -> assertEquals(taskRuns.size(), result.getContent().size()),
        () -> assertEquals(taskRuns.get(0).getLabel(), result.getContent().get(0).getLabel()),
        () -> assertEquals(taskRuns.get(1).getLabel(), result.getContent().get(1).getLabel()));
  }
}
