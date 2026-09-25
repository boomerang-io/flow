package io.boomerang.common.enums;

// TODO add in enum when it is supported by Tekton
// https://tekton.dev/docs/pipelines/tasks/#param-enum
public enum ParamType {
  string,
  array,
  object,
  /*
   * A string whose value is never shown to a consumer. Substituted exactly like a string; a
   * run response carries DataAdapterUtil.REDACTED in its place. Derived from the `password`
   * field type (ParameterUtil.abstractParamToRunParam), sent explicitly on a run request, or
   * acquired by a string param whose resolution substitutes in a secret (ParameterManager).
   */
  secret
}
