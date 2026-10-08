package io.boomerang.dispatcher.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.boomerang.dispatcher.sdk.model.RunParam;
import io.boomerang.dispatcher.sdk.model.TaskRun;
import io.boomerang.dispatcher.sdk.model.TaskType;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The transfer a non-container dispatcher performs for the artifact task types, from the link
 * params the engine fills at claim time: a PUT for an upload, a GET verified against its SHA-256
 * for a download, both to the store and carrying the link's headers.
 */
class ArtifactTransferTest {

  // A signed link: its encoded characters must reach the store exactly as issued.
  private static final String LINK =
      "http://store:8333/flow/runs/r-1/report.txt?X-Amz-Signature=ab%2Fcd&X-Amz-Expires=900";

  private static final byte[] CONTENT = "the report".getBytes(StandardCharsets.UTF_8);

  @TempDir Path dir;

  private MockRestServiceServer store;
  private RestClient http;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    store = MockRestServiceServer.bindTo(builder).build();
    http = builder.build();
  }

  private static String sha256(byte[] content) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
  }

  private static TaskRun task(TaskType type, RunParam... params) {
    TaskRun task = new TaskRun();
    task.setId("task-1");
    task.setType(type);
    task.setParams(List.of(params));
    return task;
  }

  private ArtifactTransfer upload() {
    return new ArtifactTransfer(
        http,
        task(
            TaskType.uploadartifact,
            new RunParam("name", "report.txt"),
            new RunParam("url", LINK),
            new RunParam("headers", "{\"x-amz-meta-run\":\"r-1\"}")));
  }

  private ArtifactTransfer download(String sha256) {
    return new ArtifactTransfer(
        http,
        task(
            TaskType.downloadartifact,
            new RunParam("name", "report.txt"),
            new RunParam("url", LINK),
            new RunParam("headers", "{\"x-amz-meta-run\":\"r-1\"}"),
            new RunParam("sha256", sha256),
            new RunParam("contentType", "application/octet-stream")));
  }

  @Test
  void anUploadPutsTheContentToTheLinkWithItsHeaders() throws Exception {
    Path file = Files.write(dir.resolve("report.txt"), CONTENT);
    store
        .expect(requestTo(URI.create(LINK)))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(header("x-amz-meta-run", "r-1"))
        .andExpect(header("Content-Type", "application/octet-stream"))
        .andExpect(header("Content-Length", String.valueOf(CONTENT.length)))
        .andExpect(headerDoesNotExist("Authorization"))
        .andExpect(content().bytes(CONTENT))
        .andRespond(withSuccess());

    upload().upload(file);

    store.verify();
  }

  @Test
  void aFolderUploadCarriesTheFolderContentType() {
    store
        .expect(requestTo(URI.create(LINK)))
        .andExpect(header("Content-Type", ArtifactTransfer.FOLDER_CONTENT_TYPE))
        .andRespond(withSuccess());

    upload().upload(CONTENT, ArtifactTransfer.FOLDER_CONTENT_TYPE);

    store.verify();
  }

  @Test
  void anUploadTheStoreRefusesFailsTheTaskWithTheStatus() {
    store
        .expect(requestTo(URI.create(LINK)))
        .andRespond(withStatus(HttpStatus.FORBIDDEN).body("SignatureDoesNotMatch"));

    assertThatThrownBy(() -> upload().upload(CONTENT, ArtifactTransfer.FILE_CONTENT_TYPE))
        .isInstanceOf(TaskFailure.class)
        .hasMessageContaining("403")
        .hasMessageContaining("SignatureDoesNotMatch");
  }

  @Test
  void aDownloadReturnsTheContentOnceItsSha256Matches() throws Exception {
    store
        .expect(requestTo(URI.create(LINK)))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("x-amz-meta-run", "r-1"))
        .andRespond(withSuccess(CONTENT, MediaType.APPLICATION_OCTET_STREAM));

    assertThat(download(sha256(CONTENT).toUpperCase()).download()).isEqualTo(CONTENT);
  }

  @Test
  void aDownloadIntoAFolderIsWrittenUnderTheArtifactsName() throws Exception {
    store
        .expect(requestTo(URI.create(LINK)))
        .andRespond(withSuccess(CONTENT, MediaType.APPLICATION_OCTET_STREAM));

    Path written = download(sha256(CONTENT)).download(dir);

    assertThat(written).isEqualTo(dir.resolve("report.txt"));
    assertThat(Files.readAllBytes(written)).isEqualTo(CONTENT);
  }

  @Test
  void aDownloadWhoseSha256DoesNotMatchFailsAndWritesNothing() throws Exception {
    store
        .expect(requestTo(URI.create(LINK)))
        .andRespond(withSuccess("tampered".getBytes(), MediaType.APPLICATION_OCTET_STREAM));
    Path target = dir.resolve("out").resolve("report.txt");

    assertThatThrownBy(() -> download(sha256(CONTENT)).download(target))
        .isInstanceOf(TaskFailure.class)
        .hasMessageContaining("SHA-256 mismatch");
    assertThat(target).doesNotExist();
    try (var leftovers = Files.list(target.getParent())) {
      assertThat(leftovers).isEmpty();
    }
  }

  @Test
  void aDownloadTheStoreRefusesFailsTheTaskWithTheStatus() throws Exception {
    store
        .expect(requestTo(URI.create(LINK)))
        .andRespond(withStatus(HttpStatus.NOT_FOUND).body("NoSuchKey"));

    assertThatThrownBy(() -> download(sha256(CONTENT)).download())
        .isInstanceOf(TaskFailure.class)
        .hasMessageContaining("404")
        .hasMessageContaining("NoSuchKey");
  }

  @Test
  void aDownloadWithoutAChecksumIsRefusedBeforeAnyTransfer() {
    ArtifactTransfer transfer =
        new ArtifactTransfer(
            http, task(TaskType.downloadartifact, new RunParam("url", LINK)));

    assertThatThrownBy(transfer::download)
        .isInstanceOf(TaskFailure.class)
        .hasMessageContaining("sha256");
    store.verify();
  }

  @Test
  void aTransferWithoutALinkIsRefused() {
    ArtifactTransfer transfer =
        new ArtifactTransfer(http, task(TaskType.uploadartifact, new RunParam("name", "x")));

    assertThatThrownBy(() -> transfer.upload(CONTENT, ArtifactTransfer.FILE_CONTENT_TYPE))
        .isInstanceOf(TaskFailure.class)
        .hasMessageContaining("url");
  }

  @Test
  void headersThatAreNotAJsonObjectAreRefused() {
    ArtifactTransfer transfer =
        new ArtifactTransfer(
            http,
            task(
                TaskType.uploadartifact,
                new RunParam("url", LINK),
                new RunParam("headers", "[\"not\",\"an\",\"object\"]")));

    assertThatThrownBy(() -> transfer.upload(CONTENT, ArtifactTransfer.FILE_CONTENT_TYPE))
        .isInstanceOf(TaskFailure.class)
        .hasMessageContaining("headers");
  }
}
