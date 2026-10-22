// package io.boomerang.tests;

// import java.util.Collection;
// import java.util.Collections;

// import org.springframework.beans.factory.annotation.Value;
// import org.springframework.boot.test.context.TestConfiguration;
// import org.springframework.context.annotation.Configuration;
// import org.springframework.data.mongodb.config.AbstractMongoClientConfiguration;
// import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

// import com.mongodb.ConnectionString;
// import com.mongodb.MongoClientSettings;
// import com.mongodb.MongoCredential;
// import com.mongodb.client.MongoClient;
// import com.mongodb.client.MongoClients;

// @Configuration
// @EnableMongoRepositories(basePackages = "io.boomerang.mongo.repository")
// public class MongoConfig extends AbstractMongoClientConfiguration {

//   @Value("${mongodb.database-name}")
//   private String databaseName;

//   @Value("${mongodb.connection-string}")
//   private String connectionStringName;

//   @Value("${mongodb.username}")
//   private String username;

//   @Value("${mongodb.password}")
//   private String password;

//   @Override
//   protected String getDatabaseName() {
//     return databaseName;
//   }

//   @Override
//   public MongoClient mongoClient() {
//     ConnectionString connectionString = new ConnectionString(connectionStringName);
//     MongoClientSettings mongoClientSettings = MongoClientSettings.builder()
//             .applyConnectionString(connectionString)

//             .build();
//     return MongoClients.create(mongoClientSettings);
//   }

//   @Override
//   public Collection getMappingBasePackages() {
//     return Collections.singleton("io.boomerang");
//   }
// }