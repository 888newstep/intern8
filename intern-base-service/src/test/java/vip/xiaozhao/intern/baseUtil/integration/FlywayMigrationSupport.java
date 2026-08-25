package vip.xiaozhao.intern.baseUtil.integration;

import org.flywaydb.core.Flyway;

import javax.sql.DataSource;

final class FlywayMigrationSupport {

    private FlywayMigrationSupport() {
    }

    static void migrate(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource must not be null");
        }

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .validateOnMigrate(true)
                .cleanDisabled(true)
                .load()
                .migrate();
    }

}
