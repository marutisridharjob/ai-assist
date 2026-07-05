package com.aiassist;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class AiAssistApplication {

    public static void main(String[] args) {
        // headless(false) lets the app open the browser UI when double-clicked.
        new SpringApplicationBuilder(AiAssistApplication.class).headless(false).run(args);
    }
}
