package com.pgdatamodeler.persistence;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Creates the directory holding the SQLite database file before the pool opens it. */
@Configuration(proxyBeanMethods = false)
public class SqliteDataSourceConfig {

    private static final String PREFIX = "jdbc:sqlite:";

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties properties) {
        ensureParentDirectory(properties.determineUrl());
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    static void ensureParentDirectory(String url) {
        if (url == null || !url.startsWith(PREFIX)) {
            return;
        }
        String file = url.substring(PREFIX.length());
        int query = file.indexOf('?');
        if (query >= 0) {
            file = file.substring(0, query);
        }
        if (file.isBlank() || file.startsWith(":memory:") || file.startsWith("file:")) {
            return;
        }
        Path parent = Path.of(file).toAbsolutePath().getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create directory for SQLite database: " + parent, e);
        }
    }
}
