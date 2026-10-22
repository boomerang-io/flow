package io.boomerang.flow.sdk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.util.UriBuilder;

/**
 * The query parameters of one call, in the order added. A list value is sent as one parameter per
 * element, which the server reads as the list; a null or empty value is not sent.
 */
final class Params {

  private final Map<String, List<String>> values = new LinkedHashMap<>();

  static Params none() {
    return new Params();
  }

  Params add(String name, Object value) {
    if (value != null) {
      values.computeIfAbsent(name, key -> new ArrayList<>()).add(value.toString());
    }
    return this;
  }

  Params addAll(String name, Collection<?> list) {
    if (list != null) {
      list.forEach(value -> add(name, value));
    }
    return this;
  }

  /** The names sent, for tests of what a query builder maps to. */
  Map<String, List<String>> values() {
    return values;
  }

  // Each value becomes a URI variable, so it is strictly encoded on expansion.
  void apply(UriBuilder builder, Map<String, Object> variables) {
    values.forEach(
        (name, list) -> {
          Object[] placeholders = new Object[list.size()];
          for (int i = 0; i < list.size(); i++) {
            String variable = "q" + variables.size();
            variables.put(variable, list.get(i));
            placeholders[i] = "{" + variable + "}";
          }
          builder.queryParam(name, placeholders);
        });
  }
}
