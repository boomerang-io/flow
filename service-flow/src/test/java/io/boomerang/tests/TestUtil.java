package io.boomerang.tests;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Scanner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class TestUtil {

  private TestUtil() {
    // Do nothing
  }

  public static String getMockFile(String path) throws IOException {

    ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
    File file = new File(classLoader.getResource(path).getFile());

    return new String(Files.readAllBytes(Paths.get(file.getPath())), StandardCharsets.UTF_8);
  }

  public static String parseToJson(final Object template) throws JsonProcessingException {
    return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(template);
  }

  public static String loadResourceAsString(String fileName) {
    ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
    Scanner scanner =
        new Scanner(classLoader.getResourceAsStream(fileName), StandardCharsets.UTF_8.name());
    String contents = scanner.useDelimiter("\\A").next();
    scanner.close();
    return contents;
  }

}
