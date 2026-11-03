package com.example.schedulingsoftware.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.example.schedulingsoftware.notify.NotificationChannel;
import com.example.schedulingsoftware.notify.NotificationKind;
import com.example.schedulingsoftware.notify.NotificationStatus;

import lombok.extern.slf4j.Slf4j;

/**
 * Keeps PostgreSQL's "allowed values" checks in step with the code. Hibernate adds a check such as
 * {@code channel IN ('EMAIL','WHATSAPP')} when it first creates a table, but never changes it afterwards
 * ({@code ddl-auto: update}), so a value added later (like SMS) would be refused by a database created by an
 * older version. On startup this replaces each listed check with one that allows every current value. It only adds
 * values, so an older version of the app still runs against the same database.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class EnumCheckUpkeep implements ApplicationRunner {

    /** Table, column, and the enum whose names it stores. Add a line when an enum stored in a table gains a value. */
    record EnumColumn(String table, String column, Class<? extends Enum<?>> type) {
    }

    static final List<EnumColumn> COLUMNS = List.of(
            new EnumColumn("notifications", "channel", NotificationChannel.class),
            new EnumColumn("notifications", "kind", NotificationKind.class),
            new EnumColumn("notifications", "status", NotificationStatus.class));

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public EnumCheckUpkeep(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!isPostgres()) {
            return;
        }
        for (EnumColumn c : COLUMNS) {
            String name = c.table() + "_" + c.column() + "_check";
            String allowed = Arrays.stream(c.type().getEnumConstants())
                    .map(e -> "'" + e.name() + "'")
                    .collect(Collectors.joining(","));
            String wanted = "CHECK (" + c.column() + " IN (" + allowed + "))";
            List<String> current = jdbc.queryForList(
                    "select pg_get_constraintdef(oid) from pg_constraint where conname = ? and conrelid = to_regclass(?)",
                    String.class, name, c.table());
            boolean upToDate = !current.isEmpty() && Arrays.stream(c.type().getEnumConstants())
                    .allMatch(e -> current.getFirst().contains("'" + e.name() + "'"));
            if (upToDate) {
                continue;
            }
            jdbc.execute("ALTER TABLE " + c.table() + " DROP CONSTRAINT IF EXISTS " + name);
            jdbc.execute("ALTER TABLE " + c.table() + " ADD CONSTRAINT " + name + " " + wanted);
            log.info("Updated {} to allow {}", name, allowed);
        }
    }

    private boolean isPostgres() {
        try (Connection c = dataSource.getConnection()) {
            return c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres");
        } catch (SQLException e) {
            log.warn("Couldn't check the database type: {}", e.getMessage());
            return false;
        }
    }
}
