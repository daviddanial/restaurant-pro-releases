package com.telerad.ingest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.UUID;

/**
 * Serialises concurrent ingests that would create the same patient / study rows. Without it two
 * uploads of one study both find no row, both insert, and the later commit fails on the unique key.
 */
@Component
@Slf4j
public class IngestLocks {
    private final JdbcTemplate jdbc;
    private final boolean postgres;

    public IngestLocks(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        boolean pg = false;
        try (Connection c = dataSource.getConnection()) {
            pg = c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres");
        } catch (Exception e) {
            log.warn("Could not detect database type; ingest locks disabled: {}", e.getMessage());
        }
        this.postgres = pg;
    }

    /** Held until the current transaction ends. No-op on databases without advisory locks (tests). */
    public void lockPatient(UUID tenantId, String patientId) {
        if (!postgres) return;
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null,
                tenantId + "|" + patientId);
    }
}
