package io.boomerang.common.enums;

/*
 * The life of an artifact: its record is written when the upload link is issued (uploading),
 * becomes available once the stored file is verified, and turns expired when retention ends and
 * the stored file is deleted. The record itself is removed only by a delete or its workflow's
 * prune, so an expired artifact still shows what the run produced.
 */
public enum ArtifactStatus {
  uploading,
  available,
  expired
}
