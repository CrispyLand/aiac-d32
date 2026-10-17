package com.crispyland.mcpserver.support;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "support")
public record SupportProperties(String directory) {

    public SupportProperties {
        directory = (directory == null || directory.isBlank()) ? "./support-data" : directory.strip();
    }

    public Path path() {
        return Path.of(directory).toAbsolutePath().normalize();
    }
}
