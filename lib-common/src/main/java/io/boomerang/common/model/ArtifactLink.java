package io.boomerang.common.model;

import java.util.Date;
import java.util.Map;

/**
 * A short-lived link to one artifact file, for one method: the upload task PUTs to an upload link,
 * sending {@code headers} with the request (some stores require one, e.g. Azure Blob's blob type),
 * and the download task GETs a download link and checks the file against {@code sha256}, which is
 * null on an upload link.
 */
public record ArtifactLink(
    String name, String url, Map<String, String> headers, Date expiresAt, String sha256) {}
