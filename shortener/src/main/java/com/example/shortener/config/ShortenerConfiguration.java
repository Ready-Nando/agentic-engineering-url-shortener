package com.example.shortener.config;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Map;
import java.util.random.RandomGenerator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.server.MimeMappings;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ShortenerProperties.class)
public class ShortenerConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    // Codes must not be predictable, otherwise links could be enumerated.
    @Bean
    RandomGenerator shortCodeRandom() {
        return new SecureRandom();
    }

    // Tomcat has no mapping for .yaml and would serve /openapi.yaml as application/octet-stream.
    @Bean
    WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> yamlMimeMapping() {
        return factory -> factory.addMimeMappings(new MimeMappings(Map.of("yaml", "application/yaml")));
    }
}
