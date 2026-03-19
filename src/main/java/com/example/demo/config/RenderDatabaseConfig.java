package com.example.demo.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

/**
 * RenderDatabaseConfig — faqat "render" profilida ishga tushadi.
 *
 * Muammo:
 *   Render PostgreSQL URL ni "postgres://user:pass@host/db" formatda beradi.
 *   Spring "jdbc:postgresql://host/db" formatini kutadi.
 *
 * Yechim:
 *   DATABASE_URL environment variable dan URL olib,
 *   jdbc:postgresql:// formatga o'tkazadi va SSL qo'shadi.
 */
@Configuration
@Profile("render")
public class RenderDatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(RenderDatabaseConfig.class);

    @Value("${DATABASE_URL:}")
    private String databaseUrl;

    @Value("${SPRING_DATASOURCE_USERNAME:}")
    private String username;

    @Value("${SPRING_DATASOURCE_PASSWORD:}")
    private String password;

    @Bean
    public DataSource dataSource() {
        String jdbcUrl = convertToJdbc(databaseUrl);
        log.info("Render DB ulanish: {}", jdbcUrl.replaceAll("password=[^&]*", "password=***"));

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("org.postgresql.Driver");
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(30_000);
        config.setIdleTimeout(300_000);
        config.addDataSourceProperty("sslmode", "require");

        return new HikariDataSource(config);
    }

    /**
     * postgres://user:pass@host:port/dbname
     *   → jdbc:postgresql://host:port/dbname?user=user&password=pass&sslmode=require
     */
    private String convertToJdbc(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("DATABASE_URL environment variable sozlanmagan!");
        }

        // Allaqachon jdbc formatda bo'lsa — o'zgartirma
        if (url.startsWith("jdbc:")) {
            return url.contains("sslmode") ? url : url + "?sslmode=require";
        }

        // postgres://user:pass@host:5432/dbname → jdbc:postgresql://host:5432/dbname
        try {
            String stripped = url.replace("postgres://", "").replace("postgresql://", "");
            // user:pass@host:port/db
            String[] atParts  = stripped.split("@", 2);
            String   hostPart = atParts[1]; // host:port/db
            return "jdbc:postgresql://" + hostPart + "?sslmode=require";
        } catch (Exception e) {
            throw new IllegalStateException("DATABASE_URL formati noto'g'ri: " + url, e);
        }
    }
}