package io.boomerang.core.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * T6-3: the workspace/workflow token classes (and their "bft"/"bfw" prefixes) are RETIRED
 * outright, with no deprecation window (maintainer ruling — operators re-issue tokens). Only the
 * four live-class prefixes ({@code bfg}/{@code bfk}/{@code bfu}/{@code bfs}) are ever accepted;
 * a retired-prefix bearer fails the cheap pre-DB shape gate exactly like any non-Flow bearer,
 * before {@code TokenService} ever touches Mongo.
 */
class TokenTypePrefixTest {

  @Test
  void keyTokensMintWithTheKeyPrefix() {
    assertThat(TokenTypePrefix.key.getPrefix()).isEqualTo("bfk");
    assertThat(TokenTypePrefix.valueOfPrefix("bfk")).isEqualTo(TokenTypePrefix.key);
  }

  @Test
  void retiredPrefixesAreRejectedWithoutAnyDbLookup() {
    // The gate itself: shape-only, no Mongo involved - proves a "bft_"/"bfw_" bearer never
    // reaches TokenService/TokenRepository at all.
    assertThat(TokenTypePrefix.isFlowToken("bft_abc-123")).isFalse();
    assertThat(TokenTypePrefix.isFlowToken("bfw_abc-123")).isFalse();
    // And valueOfPrefix resolves neither retired prefix to any class.
    assertThat(TokenTypePrefix.valueOfPrefix("bft")).isNull();
    assertThat(TokenTypePrefix.valueOfPrefix("bfw")).isNull();
  }

  @Test
  void isFlowTokenAcceptsExactlyTheFourLiveClassPrefixes() {
    assertThat(TokenTypePrefix.isFlowToken("bfg_" + "abc-123")).isTrue();
    assertThat(TokenTypePrefix.isFlowToken("bfk_" + "abc-123")).isTrue();
    assertThat(TokenTypePrefix.isFlowToken("bfu_" + "abc-123")).isTrue();
    assertThat(TokenTypePrefix.isFlowToken("bfs_" + "abc-123")).isTrue();
  }

  @Test
  void isFlowTokenRejectsUnknownPrefixesAndShapes() {
    assertThat(TokenTypePrefix.isFlowToken("bfx_abc")).isFalse();
    assertThat(TokenTypePrefix.isFlowToken("not-a-flow-token")).isFalse();
    assertThat(TokenTypePrefix.isFlowToken(null)).isFalse();
  }

  @Test
  void everyPrefixIsUniqueAndThreeCharacters() {
    assertThat(TokenTypePrefix.values()).hasSize(4);
    for (TokenTypePrefix type : TokenTypePrefix.values()) {
      assertThat(type.getPrefix()).hasSize(3).startsWith("bf");
    }
  }
}
