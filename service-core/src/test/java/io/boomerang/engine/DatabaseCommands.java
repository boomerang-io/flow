package io.boomerang.engine;

import com.mongodb.event.CommandFailedEvent;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import com.mongodb.event.CommandSucceededEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.RawBsonDocument;
import org.bson.codecs.BsonDocumentCodec;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Records the MongoDB commands the calling thread issues while an operation runs, with the size of
 * each reply - the database cost of that operation. The driver calls a command listener on the
 * thread that runs the command, so work the operation hands to another thread is not recorded.
 * Connection housekeeping (handshakes, authentication, session ends) is not counted.
 */
public final class DatabaseCommands implements CommandListener {

  private static final Set<String> HOUSEKEEPING =
      Set.of(
          "hello", "isMaster", "ismaster", "ping", "buildInfo", "saslStart", "saslContinue",
          "endSessions", "killCursors");

  static final DatabaseCommands LISTENER = new DatabaseCommands();

  /** One command: its name, the collection it addressed and the bytes of its reply. */
  public record Command(String name, String collection, long replyBytes) {
    @Override
    public String toString() {
      return name + " " + collection;
    }
  }

  /** What an operation returned, and the commands it issued. */
  public record Recorded<T>(T result, List<Command> commands) {
    /** The commands as "name collection", in order. */
    public List<String> names() {
      return commands.stream().map(Command::toString).toList();
    }

    public long replyBytes() {
      return commands.stream().mapToLong(Command::replyBytes).sum();
    }
  }

  private final ThreadLocal<List<Command>> recording = new ThreadLocal<>();
  private final ThreadLocal<String> pendingCollection = new ThreadLocal<>();

  private DatabaseCommands() {}

  /** Run the operation on this thread and return what it issued. */
  public static <T> Recorded<T> record(Supplier<T> operation) {
    List<Command> commands = new ArrayList<>();
    LISTENER.recording.set(commands);
    try {
      T result = operation.get();
      return new Recorded<>(result, List.copyOf(commands));
    } finally {
      LISTENER.recording.remove();
      LISTENER.pendingCollection.remove();
    }
  }

  @Override
  public void commandStarted(CommandStartedEvent event) {
    if (recording.get() != null && !HOUSEKEEPING.contains(event.getCommandName())) {
      BsonValue target = event.getCommand().get(event.getCommandName());
      pendingCollection.set((target != null && target.isString()) ? target.asString().getValue() : "");
    }
  }

  @Override
  public void commandSucceeded(CommandSucceededEvent event) {
    List<Command> commands = recording.get();
    if (commands != null && !HOUSEKEEPING.contains(event.getCommandName())) {
      BsonDocument reply = event.getResponse();
      long bytes =
          (reply != null)
              ? new RawBsonDocument(reply, new BsonDocumentCodec()).getByteBuffer().remaining()
              : 0;
      commands.add(new Command(event.getCommandName(), pendingCollection.get(), bytes));
    }
  }

  // A refused command - a duplicate key, say - is still a round trip.
  @Override
  public void commandFailed(CommandFailedEvent event) {
    List<Command> commands = recording.get();
    if (commands != null && !HOUSEKEEPING.contains(event.getCommandName())) {
      commands.add(new Command(event.getCommandName() + "(failed)", pendingCollection.get(), 0));
    }
  }

  /** Registers the listener on the application's MongoDB client. */
  @TestConfiguration(proxyBeanMethods = false)
  public static class Registration {
    @Bean
    MongoClientSettingsBuilderCustomizer databaseCommandsListener() {
      return builder -> builder.addCommandListener(LISTENER);
    }
  }
}
