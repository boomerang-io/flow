package io.boomerang.config;

import io.boomerang.client.EngineClient;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class StartupConfig {

  @Autowired private EngineClient engineClient;

  @PostConstruct
  public void init() {
    // Register Agent into the Engine
    engineClient.registerAgent();
  }
}
