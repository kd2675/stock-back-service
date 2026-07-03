package stock.back.service.market.biz;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BatchJobRuntimeControlServiceTest {

    @Test
    void status_schedulerConfiguredFalseFromDatabase_keepsEffectiveDisabledWithoutBatchHttpCall() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        BatchJobRuntimeControlService service = new BatchJobRuntimeControlService(JdbcClient.create(jdbcTemplate));
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                """
                insert into stock_batch_job_control(
                    job_name,
                    runtime_enabled,
                    scheduler_configured,
                    updated_by,
                    created_at,
                    updated_at
                )
                values (?, ?, ?, ?, ?, ?)
                """,
                "auto-market",
                true,
                false,
                "stock-batch-service",
                now,
                now
        );

        var status = service.update("auto-market", true, "admin-user");

        assertThat(status.schedulerConfigured()).isFalse();
        assertThat(status.runtimeEnabled()).isTrue();
        assertThat(status.effectiveEnabled()).isFalse();
        assertThat(status.updatedBy()).isEqualTo("admin-user");
    }

    private JdbcTemplate createJdbcTemplate() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(createDataSource());
        jdbcTemplate.execute("""
                create table stock_batch_job_control (
                  job_name varchar(100) not null primary key,
                  runtime_enabled boolean not null default true,
                  scheduler_configured boolean not null default true,
                  updated_by varchar(64),
                  created_at timestamp not null,
                  updated_at timestamp not null,
                  constraint chk_stock_batch_job_control_name check (job_name <> '')
                )
                """);
        return jdbcTemplate;
    }

    private DataSource createDataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:batch_job_runtime_control_service_%s;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false"
                .formatted(UUID.randomUUID()));
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
