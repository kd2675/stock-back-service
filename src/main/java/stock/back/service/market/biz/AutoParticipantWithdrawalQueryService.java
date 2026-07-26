package stock.back.service.market.biz;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.back.service.market.vo.AutoParticipantShareTransferResponse;
import stock.back.service.market.vo.AutoParticipantWithdrawalAuditResponse;

@Service
public class AutoParticipantWithdrawalQueryService {

    private static final int MAX_USER_KEY_FILTER_COUNT = 500;

    private static final String WITHDRAWAL_SELECT = """
            select withdrawal.id as withdrawal_id,
                   withdrawal.participant_user_key,
                   withdrawal.account_id as source_account_id,
                   source_account.status as source_account_status,
                   source_account.cash_balance as source_remaining_cash_amount,
                   coalesce((
                       select sum(holding.quantity)
                         from stock_holding holding
                        where holding.account_id = withdrawal.account_id
                   ), 0) as source_remaining_share_quantity,
                   coalesce((
                       select sum(holding.reserved_quantity)
                         from stock_holding holding
                        where holding.account_id = withdrawal.account_id
                   ), 0) as source_remaining_reserved_share_quantity,
                   coalesce((
                       select count(*)
                         from stock_order order_row
                        where order_row.account_id = withdrawal.account_id
                          and order_row.status in ('PENDING', 'PARTIALLY_FILLED')
                   ), 0) as source_open_order_count,
                   coalesce((
                       select count(*)
                         from stock_corporate_action_entitlement entitlement
                        where entitlement.account_id = withdrawal.account_id
                          and entitlement.status in (
                              'ANNOUNCED', 'PARTIALLY_SUBSCRIBED', 'SUBSCRIBED'
                          )
                   ), 0) as pending_corporate_action_right_count,
                   withdrawal.returned_cash_amount,
                   withdrawal.returned_share_quantity,
                   withdrawal.returned_symbol_count,
                   withdrawal.created_by,
                   withdrawal.created_at
              from stock_auto_participant_withdrawal withdrawal
              join stock_account source_account
                on source_account.id = withdrawal.account_id
            """;

    private static final String TRANSFER_SELECT = """
            select share_return.withdrawal_id,
                   share_return.symbol,
                   share_return.receiver_account_id,
                   receiver.user_key as receiver_user_key,
                   share_return.receiver_role,
                   share_return.transfer_reason,
                   receiver.status as receiver_account_status,
                   receiver.self_trade_group_id as receiver_self_trade_group_id,
                   share_return.quantity,
                   share_return.source_average_price,
                   coalesce(receiver_holding.quantity, 0) as receiver_current_quantity,
                   coalesce(receiver_holding.reserved_quantity, 0) as receiver_reserved_quantity,
                   coalesce(receiver_holding.average_price, 0) as receiver_average_price,
                   coalesce(price.current_price, 0) as current_price,
                   coalesce(price.current_price, 0) * share_return.quantity as transfer_market_value,
                   share_return.created_at
              from stock_auto_participant_share_return share_return
              join stock_auto_participant_withdrawal withdrawal
                on withdrawal.id = share_return.withdrawal_id
              join stock_account receiver
                on receiver.id = share_return.receiver_account_id
              left join stock_holding receiver_holding
                on receiver_holding.account_id = share_return.receiver_account_id
               and receiver_holding.symbol = share_return.symbol
              left join stock_price price
                on price.symbol = share_return.symbol
            """;

    private final JdbcClient jdbcClient;

    public AutoParticipantWithdrawalQueryService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(readOnly = true)
    public List<AutoParticipantWithdrawalAuditResponse> getWithdrawalAudits(List<String> userKeys) {
        List<String> normalizedUserKeys = normalizeUserKeys(userKeys);
        List<WithdrawalHeader> headers = queryHeaders(normalizedUserKeys);
        if (headers.isEmpty()) {
            return List.of();
        }
        Map<Long, List<AutoParticipantShareTransferResponse>> transfersByWithdrawalId =
                queryTransfers(normalizedUserKeys);
        return headers.stream()
                .map(header -> header.toResponse(
                        transfersByWithdrawalId.getOrDefault(header.withdrawalId(), List.of())
                ))
                .toList();
    }

    private List<WithdrawalHeader> queryHeaders(List<String> userKeys) {
        RowMapper<WithdrawalHeader> mapper = (rs, rowNum) -> mapHeader(rs);
        String orderBy = " order by withdrawal.created_at desc, withdrawal.id desc";
        if (userKeys.isEmpty()) {
            return jdbcClient.sql(WITHDRAWAL_SELECT + orderBy).query(mapper).list();
        }
        return jdbcClient.sql(
                        WITHDRAWAL_SELECT
                                + " where withdrawal.participant_user_key in (:userKeys)"
                                + orderBy
                )
                .param("userKeys", userKeys)
                .query(mapper)
                .list();
    }

    private Map<Long, List<AutoParticipantShareTransferResponse>> queryTransfers(List<String> userKeys) {
        RowMapper<TransferRow> mapper = (rs, rowNum) -> mapTransfer(rs);
        String orderBy = " order by share_return.withdrawal_id desc, share_return.symbol asc";
        List<TransferRow> rows;
        if (userKeys.isEmpty()) {
            rows = jdbcClient.sql(TRANSFER_SELECT + orderBy).query(mapper).list();
        } else {
            rows = jdbcClient.sql(
                            TRANSFER_SELECT
                                    + " where withdrawal.participant_user_key in (:userKeys)"
                                    + orderBy
                    )
                    .param("userKeys", userKeys)
                    .query(mapper)
                    .list();
        }
        Map<Long, List<AutoParticipantShareTransferResponse>> transfersByWithdrawalId =
                new LinkedHashMap<>();
        for (TransferRow row : rows) {
            transfersByWithdrawalId
                    .computeIfAbsent(row.withdrawalId(), ignored -> new ArrayList<>())
                    .add(row.transfer());
        }
        return transfersByWithdrawalId;
    }

    private WithdrawalHeader mapHeader(ResultSet rs) throws SQLException {
        return new WithdrawalHeader(
                rs.getLong("withdrawal_id"),
                rs.getString("participant_user_key"),
                rs.getLong("source_account_id"),
                rs.getString("source_account_status"),
                zeroIfNull(rs.getBigDecimal("source_remaining_cash_amount")),
                rs.getLong("source_remaining_share_quantity"),
                rs.getLong("source_remaining_reserved_share_quantity"),
                rs.getLong("source_open_order_count"),
                rs.getLong("pending_corporate_action_right_count"),
                zeroIfNull(rs.getBigDecimal("returned_cash_amount")),
                rs.getLong("returned_share_quantity"),
                rs.getInt("returned_symbol_count"),
                rs.getString("created_by"),
                rs.getObject("created_at", LocalDateTime.class)
        );
    }

    private TransferRow mapTransfer(ResultSet rs) throws SQLException {
        return new TransferRow(
                rs.getLong("withdrawal_id"),
                new AutoParticipantShareTransferResponse(
                        rs.getString("symbol"),
                        rs.getLong("receiver_account_id"),
                        rs.getString("receiver_user_key"),
                        rs.getString("receiver_role"),
                        rs.getString("transfer_reason"),
                        rs.getString("receiver_account_status"),
                        rs.getString("receiver_self_trade_group_id"),
                        rs.getLong("quantity"),
                        zeroIfNull(rs.getBigDecimal("source_average_price")),
                        rs.getLong("receiver_current_quantity"),
                        rs.getLong("receiver_reserved_quantity"),
                        zeroIfNull(rs.getBigDecimal("receiver_average_price")),
                        zeroIfNull(rs.getBigDecimal("current_price")),
                        zeroIfNull(rs.getBigDecimal("transfer_market_value")),
                        rs.getObject("created_at", LocalDateTime.class)
                )
        );
    }

    private List<String> normalizeUserKeys(List<String> userKeys) {
        if (userKeys == null || userKeys.isEmpty()) {
            return List.of();
        }
        List<String> normalized = userKeys.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (normalized.size() > MAX_USER_KEY_FILTER_COUNT) {
            throw new IllegalArgumentException(
                    "Withdrawal audit user-key filter exceeds " + MAX_USER_KEY_FILTER_COUNT
            );
        }
        return normalized;
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record WithdrawalHeader(
            long withdrawalId,
            String participantUserKey,
            long sourceAccountId,
            String sourceAccountStatus,
            BigDecimal sourceRemainingCashAmount,
            long sourceRemainingShareQuantity,
            long sourceRemainingReservedShareQuantity,
            long sourceOpenOrderCount,
            long pendingCorporateActionRightCount,
            BigDecimal returnedCashAmount,
            long returnedShareQuantity,
            int returnedSymbolCount,
            String createdBy,
            LocalDateTime createdAt
    ) {
        private AutoParticipantWithdrawalAuditResponse toResponse(
                List<AutoParticipantShareTransferResponse> transfers
        ) {
            return new AutoParticipantWithdrawalAuditResponse(
                    withdrawalId,
                    participantUserKey,
                    sourceAccountId,
                    sourceAccountStatus,
                    sourceRemainingCashAmount,
                    sourceRemainingShareQuantity,
                    sourceRemainingReservedShareQuantity,
                    sourceOpenOrderCount,
                    pendingCorporateActionRightCount,
                    returnedCashAmount,
                    returnedShareQuantity,
                    returnedSymbolCount,
                    createdBy,
                    createdAt,
                    transfers
            );
        }
    }

    private record TransferRow(
            long withdrawalId,
            AutoParticipantShareTransferResponse transfer
    ) {
    }
}
