package stock.back.service.database.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import stock.back.service.database.entity.StockCorporateActionStatus;
import stock.back.service.database.entity.StockCorporateActionType;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class StockCorporateActionRepositoryFeedTest {

    @Autowired
    private StockCorporateActionRepository stockCorporateActionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from stock_corporate_action where symbol like 'ZQFEED%'");
    }

    @Test
    void findActivePaidInActions_mixedStatuses_returnsLifecycleActiveInStableOrder() {
        LocalDateTime olderCreatedAt = LocalDateTime.of(2026, 7, 1, 9, 0);
        LocalDateTime tiedCreatedAt = LocalDateTime.of(2026, 7, 2, 9, 0);
        Long announcedId = insertAction("ZQFEED01", "PAID_IN_CAPITAL_INCREASE", "ANNOUNCED", olderCreatedAt);
        Long firstTiedId = insertAction("ZQFEED02", "PAID_IN_CAPITAL_INCREASE", "EX_RIGHTS_APPLIED", tiedCreatedAt);
        Long secondTiedId = insertAction("ZQFEED03", "PAID_IN_CAPITAL_INCREASE", "PAID", tiedCreatedAt);
        insertAction("ZQFEED04", "PAID_IN_CAPITAL_INCREASE", "LISTED", tiedCreatedAt.plusDays(1));
        insertAction("ZQFEED05", "CASH_DIVIDEND", "ANNOUNCED", tiedCreatedAt.plusDays(2));

        var actions = stockCorporateActionRepository.findByActionTypeAndStatusInOrderByCreatedAtDescIdDesc(
                StockCorporateActionType.PAID_IN_CAPITAL_INCREASE,
                List.of(
                        StockCorporateActionStatus.ANNOUNCED,
                        StockCorporateActionStatus.EX_RIGHTS_APPLIED,
                        StockCorporateActionStatus.PAID
                )
        );

        assertThat(actions).extracting(action -> action.getId())
                .containsExactly(secondTiedId, firstTiedId, announcedId);
    }

    private Long insertAction(String symbol, String actionType, String status, LocalDateTime createdAt) {
        jdbcTemplate.update(
                "insert into stock_corporate_action(symbol, action_type, status, created_at) values (?, ?, ?, ?)",
                symbol,
                actionType,
                status,
                createdAt
        );
        return jdbcTemplate.queryForObject(
                "select id from stock_corporate_action where symbol = ?",
                Long.class,
                symbol
        );
    }
}
