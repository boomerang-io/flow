package io.boomerang.workflow;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/*
 * The artifact store on any S3-compatible service (AWS S3, MinIO). Links are signed by a presigner
 * that may carry a different endpoint from the client: a link must name a host the task's pod can
 * reach, which is not always the address service-core uses.
 */
public class S3ArtifactStore implements ArtifactStore {

  private final S3Client client;
  private final S3Presigner presigner;
  private final String bucket;

  public S3ArtifactStore(S3Client client, S3Presigner presigner, String bucket) {
    this.client = client;
    this.presigner = presigner;
    this.bucket = bucket;
  }

  @Override
  public Link uploadLink(String key, Duration ttl) {
    PutObjectRequest put = PutObjectRequest.builder().bucket(bucket).key(key).build();
    return new Link(
        URI.create(
            presigner
                .presignPutObject(request -> request.signatureDuration(ttl).putObjectRequest(put))
                .url()
                .toString()),
        Map.of());
  }

  @Override
  public Link downloadLink(String key, Duration ttl) {
    GetObjectRequest get = GetObjectRequest.builder().bucket(bucket).key(key).build();
    return new Link(
        URI.create(
            presigner
                .presignGetObject(request -> request.signatureDuration(ttl).getObjectRequest(get))
                .url()
                .toString()),
        Map.of());
  }

  @Override
  public Optional<StoredObject> head(String key) {
    try {
      HeadObjectResponse head =
          client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
      return Optional.of(new StoredObject(head.contentLength(), head.contentType()));
    } catch (S3Exception e) {
      // HEAD has no body, so a missing key surfaces as a bare 404 rather than NoSuchKey.
      if (e.statusCode() == 404) {
        return Optional.empty();
      }
      throw e;
    }
  }

  @Override
  public InputStream open(String key) {
    return client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
  }

  @Override
  public void delete(String key) {
    client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
  }
}
