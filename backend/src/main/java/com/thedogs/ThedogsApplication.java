package com.thedogs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
public class ThedogsApplication {

  public static void main(String[] args) {
    SpringApplication.run(ThedogsApplication.class, args);
  }
}
