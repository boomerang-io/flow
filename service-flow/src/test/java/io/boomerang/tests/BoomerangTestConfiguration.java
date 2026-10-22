// package io.boomerang.tests;

// import java.io.IOException;
// import java.util.regex.Matcher;
// import java.util.regex.Pattern;

// import org.junit.jupiter.api.BeforeAll;
// import org.junit.jupiter.api.TestInstance;
// import org.junit.jupiter.api.extension.ExtendWith;
// import org.springframework.boot.test.autoconfigure.data.mongo.AutoConfigureDataMongo;
// import org.springframework.context.annotation.Configuration;
// import org.springframework.context.annotation.Profile;
// import org.springframework.core.io.Resource;
// import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
// import org.springframework.core.io.support.ResourcePatternResolver;
// import org.springframework.test.context.ContextConfiguration;
// import org.springframework.test.context.junit.jupiter.SpringExtension;

// import com.fasterxml.jackson.databind.ObjectMapper;

// import de.flapdoodle.embed.mongo.commands.MongoImportArguments;
// import de.flapdoodle.embed.mongo.config.Net;
// import de.flapdoodle.embed.mongo.distribution.Version;
// import de.flapdoodle.embed.mongo.transitions.ExecutedMongoImportProcess;
// import de.flapdoodle.embed.mongo.transitions.MongoImport;
// import de.flapdoodle.embed.mongo.transitions.Mongod;
// import de.flapdoodle.embed.mongo.transitions.RunningMongodProcess;
// import de.flapdoodle.reverse.StateID;
// import de.flapdoodle.reverse.TransitionWalker;
// import de.flapdoodle.reverse.Transitions;
// import de.flapdoodle.reverse.transitions.Start;
// import io.boomerang.Application;

// @Configuration
// @Profile("embedded")
// @AutoConfigureDataMongo
// @ExtendWith(SpringExtension.class)
// @ContextConfiguration(classes={Application.class})
// @TestInstance(TestInstance.Lifecycle.PER_CLASS)
// public class BoomerangTestConfiguration {


//   private static final Pattern pattern = Pattern.compile("[.][^.]+");

//   private String database = "local";
// ;

//   private final ObjectMapper objectMapper = new ObjectMapper();
//   private TransitionWalker.ReachedState<RunningMongodProcess> mongoDProcess;
//   private TransitionWalker.ReachedState<ExecutedMongoImportProcess> mongoImportProcess;



//   @BeforeAll
//   public void setUp() throws IOException  {
//     ResourcePatternResolver resourcePatternResolver = new PathMatchingResourcePatternResolver();

//     Resource[] resources = resourcePatternResolver.getResources("classpath:db/*.json");

//     for (Resource jsonFile : resources) {
//       String filepath = jsonFile.getFile().getAbsolutePath();

//       Matcher matcher = pattern.matcher(jsonFile.getFilename());
//       String collectionName = matcher.replaceFirst("");
//       startMongoImport(database, collectionName, filepath, false, true, true);

//     }
//   }


  
//   private void startMongoImport(String dbName, String collection, String jsonFile,
//       Boolean jsonArray, Boolean upsert, Boolean drop) throws IOException {
//         MongoImportArguments arguments = MongoImportArguments.builder()
//         .databaseName(database)
//         .collectionName(collection)
//         .importFile(jsonFile)
//         .isJsonArray(true)
//         .upsertDocuments(true)
//         .build();
  
//       Version.Main version = Version.Main.V6_0;
  
//       mongoDProcess = Mongod.builder()
//         .net(Start.to(Net.class).initializedWith(Net.defaults().withPort(27019)))
//         .build()
//         .transitions(version)
//         .walker()
//         .initState(StateID.of(RunningMongodProcess.class));
  
//       Transitions mongoImportTransitions = MongoImport.instance()
//         .transitions(version)
//         .replace(Start.to(MongoImportArguments.class).initializedWith(arguments))
//         .addAll(Start.to(de.flapdoodle.embed.mongo.commands.ServerAddress.class).initializedWith(mongoDProcess.current().getServerAddress()));
  
//       mongoImportProcess = mongoImportTransitions.walker().initState(StateID.of(ExecutedMongoImportProcess.class));
//   }

// }


