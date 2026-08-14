package io.boomerang.security;

import io.boomerang.security.enums.AuthScope;
import io.boomerang.core.model.Token;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** Helpers to retrieve the idenity of the current principal from the security context. */
@Service
public class IdentityService {

  private static final Logger LOGGER = LogManager.getLogger();

  public String getCurrentPrincipal() {
    Token token = this.getCurrentIdentity();
    return token.getPrincipal();
  }

  public AuthScope getCurrentScope() {
    Token token = this.getCurrentIdentity();
    return token.getType();
  }

  public Token getCurrentIdentity() {
    if (SecurityContextHolder.getContext() != null
        && SecurityContextHolder.getContext().getAuthentication() != null
        && SecurityContextHolder.getContext().getAuthentication().getDetails() != null
        && SecurityContextHolder.getContext().getAuthentication().getDetails() instanceof Token) {
      Object details = SecurityContextHolder.getContext().getAuthentication().getDetails();
      return (Token) details;
    } else {
      return null;
    }
  }
}
