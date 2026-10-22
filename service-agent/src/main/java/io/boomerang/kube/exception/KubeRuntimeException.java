package io.boomerang.kube.exception;

public class KubeRuntimeException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public KubeRuntimeException(String exception) {
    super(exception);
  }

  public KubeRuntimeException(String exception, Throwable e) {
    super(exception, e);
  }
}
