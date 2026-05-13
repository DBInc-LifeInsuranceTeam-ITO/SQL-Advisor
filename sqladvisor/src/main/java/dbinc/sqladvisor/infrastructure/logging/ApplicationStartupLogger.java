package dbinc.sqladvisor.infrastructure.logging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.Arrays;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApplicationStartupLogger implements ApplicationRunner {

    private final Environment env;
    private final DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) {
        log.info("========================================");
        log.info("Application Started Successfully");
        log.info("========================================");

        String[] profiles = env.getActiveProfiles();
        if (profiles.length > 0) {
            log.info("Active Profile: {}", Arrays.toString(profiles));
        } else {
            log.info("Active Profile: default");
        }

        String port = env.getProperty("server.port", "8080");
        log.info("Server Port: {}", port);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String dbUrl = metaData.getURL();
            String dbProduct = metaData.getDatabaseProductName();
            String dbVersion = metaData.getDatabaseProductVersion();

            log.info("Database Connected:");
            log.info("  - URL: {}", maskSensitiveInfo(dbUrl));
            log.info("  - Product: {} {}", dbProduct, dbVersion);
        } catch (Exception e) {
            log.warn("Could not retrieve database information: {}", e.getMessage());
        }

        log.info("========================================");
    }

    private String maskSensitiveInfo(String url) {
        if (url == null) return null;

        // 비밀번호 마스킹
        String masked = url.replaceAll("password=[^;&]*", "password=****");
        // 사용자명 일부 마스킹
        masked = masked.replaceAll("(user=)([^;&]{2})([^;&]*)", "$1$2****");

        return masked;
    }
}