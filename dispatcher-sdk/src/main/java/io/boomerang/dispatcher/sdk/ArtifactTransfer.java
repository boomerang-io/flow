package io.boomerang.dispatcher.sdk;

import io.boomerang.dispatcher.sdk.model.TaskRun;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;

/**
 * The transfer an {@code uploadartifact} or {@code downloadartifact} task performs, for a
 * dispatcher that runs it without the worker image. The engine fills the task's link params when
 * it hands the task out - {@code url} and {@code headers}, and for a download {@code sha256} and
 * {@code contentType} - so the transfer talks to the artifact store directly and never to the
 * engine, and the ordinary end report completes the task: the engine verifies an upload itself.
 *
 * <p>Upload is a PUT of the content to {@code url} with every header in {@code headers}. Download
 * is a GET from {@code url} with those headers, and fails unless the content's SHA-256 matches
 * {@code sha256}. A folder artifact travels as a gzipped tarball of its contents, content type
 * {@link #FOLDER_CONTENT_TYPE}; packing and unpacking it is the caller's. Every failure is a
 * {@link TaskFailure}.
 */
public final class ArtifactTransfer {

  /** The content type of a folder artifact: a gzipped tarball of the folder's contents. */
  public static final String FOLDER_CONTENT_TYPE = "application/vnd.boomerang.artifact.tar+gzip";

  /** The content type of a file artifact. */
  public static final String FILE_CONTENT_TYPE = "application/octet-stream";

  private static final int MAX_ERROR_BODY_BYTES = 1024;

  private static final TypeReference<Map<String, Object>> HEADERS = new TypeReference<>() {};

  private final RestClient http;
  private final TaskRun task;

  /**
   * Transfer {@code task}'s artifact through {@code http}. The client MUST NOT carry engine
   * credentials: the link is signed for the artifact store, and every header the client adds is
   * sent there.
   */
  public ArtifactTransfer(RestClient http, TaskRun task) {
    this.http = http;
    this.task = task;
  }

  /** The artifact's name, the task's {@code name} param. */
  public String name() {
    return param("name");
  }

  /** The stored content type a download carries, or null on an upload. */
  public String contentType() {
    return param("contentType");
  }

  /** The SHA-256 a download is verified against, or null on an upload. */
  public String sha256() {
    return param("sha256");
  }

  /** Upload {@code file} as a file artifact. */
  public void upload(Path file) {
    upload(file, FILE_CONTENT_TYPE);
  }

  /** Upload {@code file} with {@code contentType}, {@link #FOLDER_CONTENT_TYPE} for a folder. */
  public void upload(Path file, String contentType) {
    try {
      put(Files.size(file), contentType, out -> Files.copy(file, out));
    } catch (IOException e) {
      throw new TaskFailure(
          TaskFailure.DISPATCH_ERROR, "Artifact upload failed: " + e.getMessage(), e);
    }
  }

  /** Upload {@code content} with {@code contentType}. */
  public void upload(byte[] content, String contentType) {
    put(content.length, contentType, out -> out.write(content));
  }

  /**
   * Download the artifact to {@code target}, or into it under the artifact's name when it is a
   * directory, and return the file written. Nothing is written at {@code target} unless the
   * content's SHA-256 matches.
   */
  public Path download(Path target) {
    Path file = Files.isDirectory(target) ? target.resolve(name()) : target;
    Path temp = null;
    try {
      Path parent = file.toAbsolutePath().getParent();
      Files.createDirectories(parent);
      temp = Files.createTempFile(parent, ".artifact-", ".download");
      try (OutputStream out = Files.newOutputStream(temp)) {
        verify(get(out));
      }
      return Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new TaskFailure(
          TaskFailure.DISPATCH_ERROR, "Artifact download failed: " + e.getMessage(), e);
    } finally {
      deleteQuietly(temp);
    }
  }

  /** Download the artifact into memory, verified against its SHA-256. */
  public byte[] download() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    verify(get(out));
    return out.toByteArray();
  }

  private void put(long length, String contentType, StreamingHttpOutputMessage.Body body) {
    URI url = url();
    Map<String, String> headers = headers();
    try {
      http.put()
          .uri(url)
          .headers(
              h -> {
                headers.forEach(h::set);
                h.setContentType(MediaType.parseMediaType(contentType));
                h.setContentLength(length);
              })
          .body(body)
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientResponseException e) {
      throw new TaskFailure(
          TaskFailure.DISPATCH_ERROR,
          "Artifact upload failed with status "
              + e.getStatusCode().value()
              + ": "
              + truncated(e.getResponseBodyAsString()));
    } catch (RestClientException e) {
      throw new TaskFailure(
          TaskFailure.DISPATCH_ERROR, "Artifact upload failed: " + e.getMessage(), e);
    }
  }

  // Stream the content into out and return its SHA-256, hex-encoded.
  private String get(OutputStream out) {
    URI url = url();
    Map<String, String> headers = headers();
    if (sha256() == null || sha256().isBlank()) {
      throw new TaskFailure(
          TaskFailure.DISPATCH_ERROR, "The parameter 'sha256' is not defined or empty");
    }
    try {
      return http.get()
          .uri(url)
          .headers(h -> headers.forEach(h::set))
          .exchange(
              (request, response) -> {
                if (!response.getStatusCode().is2xxSuccessful()) {
                  throw new TaskFailure(
                      TaskFailure.DISPATCH_ERROR,
                      "Artifact download failed with status "
                          + response.getStatusCode().value()
                          + ": "
                          + errorBody(response));
                }
                MessageDigest digest = sha256Digest();
                try (InputStream in = new DigestInputStream(response.getBody(), digest)) {
                  in.transferTo(out);
                }
                return HexFormat.of().formatHex(digest.digest());
              });
    } catch (RestClientException e) {
      throw new TaskFailure(
          TaskFailure.DISPATCH_ERROR, "Artifact download failed: " + e.getMessage(), e);
    }
  }

  private void verify(String actual) {
    String expected = sha256().toLowerCase(Locale.ROOT);
    if (!expected.equals(actual)) {
      throw new TaskFailure(
          TaskFailure.DISPATCH_ERROR,
          "SHA-256 mismatch: expected " + expected + ", got " + actual);
    }
  }

  private URI url() {
    String url = param("url");
    if (url == null || url.isBlank()) {
      throw new TaskFailure(
          TaskFailure.DISPATCH_ERROR, "The parameter 'url' is not defined or empty");
    }
    // A URI, never a template: the link is signed and must reach the store byte for byte.
    return URI.create(url);
  }

  // The headers param is a JSON object, possibly empty or absent; every header it lists is sent.
  private Map<String, String> headers() {
    Object value = task.param("headers");
    Map<String, Object> parsed;
    try {
      parsed =
          (value instanceof Map<?, ?> map)
              ? WireFormat.JSON.convertValue(map, HEADERS)
              : (value == null || value.toString().isBlank())
                  ? Map.of()
                  : WireFormat.JSON.readValue(value.toString(), HEADERS);
    } catch (JacksonException | IllegalArgumentException e) {
      throw new TaskFailure(
          TaskFailure.DISPATCH_ERROR, "The parameter 'headers' is not a JSON object");
    }
    Map<String, String> headers = new LinkedHashMap<>();
    parsed.forEach((name, header) -> headers.put(name, String.valueOf(header)));
    return headers;
  }

  private String param(String name) {
    Object value = task.param(name);
    return (value != null) ? value.toString() : null;
  }

  private static String errorBody(ClientHttpResponse response) {
    try (InputStream in = response.getBody()) {
      return truncated(new String(in.readNBytes(MAX_ERROR_BODY_BYTES), StandardCharsets.UTF_8));
    } catch (IOException e) {
      return "";
    }
  }

  private static String truncated(String body) {
    return (body.length() > MAX_ERROR_BODY_BYTES) ? body.substring(0, MAX_ERROR_BODY_BYTES) : body;
  }

  private static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void deleteQuietly(Path path) {
    if (path != null) {
      try {
        Files.deleteIfExists(path);
      } catch (IOException e) {
        // A temp file left behind is harmless; the transfer's own outcome stands.
      }
    }
  }
}
