package stock.back.service.trading.biz;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class AutoParticipantFundingBudgetReleaseService {

    private static final int MAX_ORDER_IDS_PER_RELEASE = 500;

    private final JdbcTemplate jdbcTemplate;

    AutoParticipantFundingBudgetReleaseService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    int releaseCancelledOrderBudgets(List<Long> orderIds, LocalDateTime releasedAt) {
        if (orderIds == null || orderIds.isEmpty()) {
            return 0;
        }
        if (releasedAt == null) {
            throw new IllegalArgumentException("Funding budget release time is required");
        }
        List<Long> normalizedOrderIds = orderIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (normalizedOrderIds.isEmpty()) {
            return 0;
        }
        if (normalizedOrderIds.size() > MAX_ORDER_IDS_PER_RELEASE) {
            throw new IllegalArgumentException(
                    "Funding budget release order chunk exceeds %d: %d"
                            .formatted(MAX_ORDER_IDS_PER_RELEASE, normalizedOrderIds.size())
            );
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(normalizedOrderIds.size(), "?"));
        List<Allocation> allocations = jdbcTemplate.query(
                """
                select ob.order_id, ob.budget_id, ob.remaining_reserved_amount
                  from stock_auto_participant_order_budget ob
                  join stock_auto_participant_funding_budget b on b.id = ob.budget_id
                 where ob.order_id in (%s)
                   and ob.remaining_reserved_amount > 0
                 order by ob.budget_id asc, ob.order_id asc
                 for update
                """.formatted(placeholders),
                (rs, rowNum) -> new Allocation(
                        rs.getLong("order_id"),
                        rs.getLong("budget_id"),
                        rs.getBigDecimal("remaining_reserved_amount")
                ),
                normalizedOrderIds.toArray()
        );
        if (allocations.isEmpty()) {
            return 0;
        }

        int[] allocationUpdates = jdbcTemplate.batchUpdate(
                """
                update stock_auto_participant_order_budget
                   set remaining_reserved_amount = remaining_reserved_amount - ?,
                       released_amount = released_amount + ?,
                       updated_at = ?
                 where order_id = ?
                   and budget_id = ?
                   and remaining_reserved_amount >= ?
                """,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int index) throws SQLException {
                        Allocation allocation = allocations.get(index);
                        ps.setBigDecimal(1, allocation.amount());
                        ps.setBigDecimal(2, allocation.amount());
                        ps.setObject(3, releasedAt);
                        ps.setLong(4, allocation.orderId());
                        ps.setLong(5, allocation.budgetId());
                        ps.setBigDecimal(6, allocation.amount());
                    }

                    @Override
                    public int getBatchSize() {
                        return allocations.size();
                    }
                }
        );
        requireSuccessfulBatch("funding order allocation release", allocationUpdates, allocations.size());

        Map<Long, BigDecimal> releasedByBudget = new LinkedHashMap<>();
        allocations.forEach(allocation -> releasedByBudget.merge(
                allocation.budgetId(),
                allocation.amount(),
                BigDecimal::add
        ));
        List<Map.Entry<Long, BigDecimal>> budgetEntries = new ArrayList<>(releasedByBudget.entrySet());
        int[] budgetUpdates = jdbcTemplate.batchUpdate(
                """
                update stock_auto_participant_funding_budget
                   set reserved_amount = reserved_amount - ?,
                       available_amount = available_amount + ?,
                       status = case
                           when expires_business_date is not null and expires_business_date < ? then 'EXPIRED'
                           else 'ACTIVE'
                       end,
                       updated_at = ?
                 where id = ?
                   and reserved_amount >= ?
                """,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int index) throws SQLException {
                        Map.Entry<Long, BigDecimal> entry = budgetEntries.get(index);
                        ps.setBigDecimal(1, entry.getValue());
                        ps.setBigDecimal(2, entry.getValue());
                        ps.setObject(3, releasedAt.toLocalDate());
                        ps.setObject(4, releasedAt);
                        ps.setLong(5, entry.getKey());
                        ps.setBigDecimal(6, entry.getValue());
                    }

                    @Override
                    public int getBatchSize() {
                        return budgetEntries.size();
                    }
                }
        );
        requireSuccessfulBatch("funding budget release", budgetUpdates, budgetEntries.size());
        return allocations.size();
    }

    private void requireSuccessfulBatch(String operation, int[] updateCounts, int expectedCount) {
        if (updateCounts.length != expectedCount) {
            throw new IllegalStateException(
                    "%s count mismatch: expected=%d, actual=%d"
                            .formatted(operation, expectedCount, updateCounts.length)
            );
        }
        for (int index = 0; index < updateCounts.length; index++) {
            int updateCount = updateCounts[index];
            if (updateCount != 1 && updateCount != Statement.SUCCESS_NO_INFO) {
                throw new IllegalStateException(
                        "%s changed during settlement: index=%d, updateCount=%d"
                                .formatted(operation, index, updateCount)
                );
            }
        }
    }

    private record Allocation(long orderId, long budgetId, BigDecimal amount) {
    }
}
