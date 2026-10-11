package io.boomerang.workflow;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/*
 * Where artifact files live. Tasks never hold store credentials: they get a short-lived link
 * scoped to one object and one method. service-core reads a file back only to verify an upload
 * and to stream a download.
 */
public interface ArtifactStore {

  /** A link that lets the holder PUT exactly one object until it expires. */
  Link uploadLink(String key, Duration ttl);

  /** A link that lets the holder GET exactly one object until it expires. */
  Link downloadLink(String key, Duration ttl);

  /** The stored object's size and content type, or empty when nothing is stored at the key. */
  Optional<StoredObject> head(String key);

  InputStream open(String key);

  /** Delete the object; deleting an absent object succeeds, so every caller may retry. */
  void delete(String key);

  record StoredObject(long size, String contentType) {}

  /** A signed URL and the headers the request must send with it (none for S3). */
  record Link(URI url, Map<String, String> headers) {}
}
