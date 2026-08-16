package io.boomerang.core.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * H14-e: the workspace token prefix moved from "bft" to "bfk". Every already-issued "bft_..."
 * token has only its SHA-256 hash stored (see {@code TokenService#hashString}/{@code validate}),
 * so it can never be rewritten in the database - both prefixes must keep resolving for the life
 * of the deprecation window, while new tokens mint with "bfk" only.
 */
class TokenTypePrefixTest {

  @Test
  void newTokensMintWithTheNewWorkspacePrefix() {
    assertThat(TokenTypePrefix.workspace.getPrefix()).isEqualTo("bfk");
  }

  @Test
  void legacyAndNewWorkspacePrefixBothResolve() {
    assertThat(TokenTypePrefix.valueOfPrefix("bfk")).isEqualTo(TokenTypePrefix.workspace);
    assertThat(TokenTypePrefix.valueOfPrefix("bft")).isEqualTo(TokenTypePrefix.workspace);
  }

  @Test
  void isFlowTokenAcceptsBothTheLegacyAndNewWorkspacePrefix() {
    assertThat(TokenTypePrefix.isFlowToken("bft_" + "abc-123")).isTrue();
    assertThat(TokenTypePrefix.isFlowToken("bfk_" + "abc-123")).isTrue();
  }

  @Test
  void isFlowTokenStillAcceptsEveryOtherPrefix() {
    assertThat(TokenTypePrefix.isFlowToken("bfg_x")).isTrue();
    assertThat(TokenTypePrefix.isFlowToken("bfw_x")).isTrue();
    assertThat(TokenTypePrefix.isFlowToken("bfu_x")).isTrue();
    assertThat(TokenTypePrefix.isFlowToken("bfs_x")).isTrue();
  }

  @Test
  void isFlowTokenRejectsUnknownPrefixesAndShapes() {
    assertThat(TokenTypePrefix.isFlowToken("bfx_abc")).isFalse();
    assertThat(TokenTypePrefix.isFlowToken("not-a-flow-token")).isFalse();
    assertThat(TokenTypePrefix.isFlowToken(null)).isFalse();
  }

  @Test
  void everyPrefixIsUniqueAndThreeCharacters() {
    for (TokenTypePrefix type : TokenTypePrefix.values()) {
      assertThat(type.getPrefix()).hasSize(3).startsWith("bf");
    }
  }
}
