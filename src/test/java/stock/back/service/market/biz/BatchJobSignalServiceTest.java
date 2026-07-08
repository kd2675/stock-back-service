package stock.back.service.market.biz;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import stock.back.service.market.vo.StockBatchJobRunResponse;

import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BatchJobSignalServiceTest {

    @Test
    void enqueueAutoParticipantCashFlow_insertsPendingSignalAndReturnsQueuedResponse() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        BatchJobSignalService service = new BatchJobSignalService(jdbcTemplate);

        StockBatchJobRunResponse response = service.enqueueAutoParticipantCashFlow("admin-user");

        assertThat(response.job()).isEqualTo("auto-participant-cash-flow");
        assertThat(response.status()).isEqualTo("QUEUED");
        assertThat(response.executionMode()).isEqualTo("manual-recurring-cash");
        Map<String, Object> row = findOnlySignal(jdbcTemplate);
        assertThat(row)
                .containsEntry("signal_type", "AUTO_PARTICIPANT_CASH_FLOW_RUN")
                .containsEntry("job_name", "auto-participant-cash-flow")
                .containsEntry("execution_mode", "manual-recurring-cash")
                .containsEntry("status", "PENDING")
                .containsEntry("requested_by", "admin-user");
        assertThat(row.get("symbol")).isNull();
    }

    @Test
    void enqueueAutoParticipantCashFlow_openSignalExists_returnsExistingSignalWithoutInsert() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        BatchJobSignalService service = new BatchJobSignalService(jdbcTemplate);

        StockBatchJobRunResponse firstResponse = service.enqueueAutoParticipantCashFlow("admin-user");
        StockBatchJobRunResponse secondResponse = service.enqueueAutoParticipantCashFlow("admin-user");

        assertThat(firstResponse.message()).contains("Batch job signal queued: id=");
        assertThat(secondResponse.message()).contains("Batch job signal already queued: id=");
        assertThat(jdbcTemplate.queryForObject("select count(*) from stock_batch_job_signal", Long.class))
                .isEqualTo(1L);
    }

    @Test
    void enqueueAutoParticipantCashFlow_completedSignalExists_insertsNewSignal() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        BatchJobSignalService service = new BatchJobSignalService(jdbcTemplate);

        service.enqueueAutoParticipantCashFlow("admin-user");
        jdbcTemplate.update("update stock_batch_job_signal set status = 'COMPLETED'");

        StockBatchJobRunResponse response = service.enqueueAutoParticipantCashFlow("admin-user");

        assertThat(response.message()).contains("Batch job signal queued: id=");
        assertThat(jdbcTemplate.queryForObject("select count(*) from stock_batch_job_signal", Long.class))
                .isEqualTo(2L);
    }

    @Test
    void enqueueMarketCloseRolloverSymbol_insertsSymbolScopedSignal() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        BatchJobSignalService service = new BatchJobSignalService(jdbcTemplate);

        StockBatchJobRunResponse response = service.enqueueMarketCloseRollover("demo001", "admin-user");

        assertThat(response.job()).isEqualTo("market-close-rollover");
        assertThat(response.status()).isEqualTo("QUEUED");
        assertThat(response.executionMode()).isEqualTo("price-limit-base:DEMO001");
        Map<String, Object> row = findOnlySignal(jdbcTemplate);
        assertThat(row)
                .containsEntry("signal_type", "MARKET_CLOSE_ROLLOVER_SYMBOL")
                .containsEntry("job_name", "market-close-rollover")
                .containsEntry("execution_mode", "price-limit-base:DEMO001")
                .containsEntry("symbol", "DEMO001")
                .containsEntry("status", "PENDING")
                .containsEntry("requested_by", "admin-user");
    }

    @Test
    void enqueueOpenOrderBookOrderCancel_insertsDeferredCancelSignal() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        BatchJobSignalService service = new BatchJobSignalService(jdbcTemplate);

        StockBatchJobRunResponse response = service.enqueueOpenOrderBookOrderCancel("demo002", "admin-user");

        assertThat(response.job()).isEqualTo("market-close-rollover");
        assertThat(response.status()).isEqualTo("QUEUED");
        assertThat(response.executionMode()).isEqualTo("halt-open-order-cancel:DEMO002");
        Map<String, Object> row = findOnlySignal(jdbcTemplate);
        assertThat(row)
                .containsEntry("signal_type", "ORDER_BOOK_OPEN_ORDER_CANCEL_SYMBOL")
                .containsEntry("job_name", "market-close-rollover")
                .containsEntry("execution_mode", "halt-open-order-cancel:DEMO002")
                .containsEntry("symbol", "DEMO002")
                .containsEntry("status", "PENDING")
                .containsEntry("requested_by", "admin-user");
    }

    private Map<String, Object> findOnlySignal(JdbcTemplate jdbcTemplate) {
        assertThat(jdbcTemplate.queryForObject("select count(*) from stock_batch_job_signal", Long.class))
                .isEqualTo(1L);
        return jdbcTemplate.queryForMap("""
                select signal_type,
                       job_name,
                       execution_mode,
                       symbol,
                       status,
                       requested_by
                  from stock_batch_job_signal
                """);
    }

    private JdbcTemplate createJdbcTemplate() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(createDataSource());
        jdbcTemplate.execute("""
                create table stock_batch_job_signal (
                  id bigint auto_increment primary key,
                  signal_type varchar(64) not null,
                  job_name varchar(100) not null,
                  execution_mode varchar(100) not null,
                  symbol varchar(20),
                  payload_json clob,
                  status varchar(20) not null,
                  requested_by varchar(64),
                  requested_at timestamp not null,
                  claimed_at timestamp,
                  completed_at timestamp,
                  message varchar(500),
                  created_at timestamp not null,
                  updated_at timestamp not null
                )
                """);
        return jdbcTemplate;
    }

    private DataSource createDataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:batch_job_signal_service_%s;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false"
                .formatted(UUID.randomUUID()));
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
