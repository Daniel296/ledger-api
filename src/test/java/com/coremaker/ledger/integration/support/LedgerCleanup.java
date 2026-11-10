package com.coremaker.ledger.integration.support;

import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.junit.jupiter.SpringExtension;

class LedgerCleanup implements BeforeEachCallback {

    private static final List<String> EMPTY_THE_LEDGER = List.of(
            "SET FOREIGN_KEY_CHECKS = 0",
            "DELETE FROM idempotent_requests",
            "DELETE FROM entries",
            "DELETE FROM transactions",
            "DELETE FROM accounts",
            "SET FOREIGN_KEY_CHECKS = 1");

    private static final ClassPathResource SETTLEMENT_ACCOUNTS =
            new ClassPathResource("db/changelog/changes/002-insert-settlement-accounts.sql");

    @Override
    public void beforeEach(ExtensionContext context) throws SQLException {

        var dataSource = SpringExtension.getApplicationContext(context).getBean(DataSource.class);

        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {

            for (var sql : EMPTY_THE_LEDGER) {

                statement.execute(sql);
            }

            ScriptUtils.executeSqlScript(connection, SETTLEMENT_ACCOUNTS);
        }
    }
}
