package com.thedogs.config;

import java.util.concurrent.Executors;
import org.apache.coyote.ProtocolHandler;
import org.springframework.boot.web.embedded.tomcat.TomcatProtocolHandlerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VirtualThreadConfig {

  @Bean
  public TomcatProtocolHandlerCustomizer<ProtocolHandler> virtualThreadCustomizer() {
    return handler -> handler.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
  }
}
