package stock.back.service.market.biz;

import stock.back.service.market.vo.AdminCorporateActionFlowSummaryResponse;
import stock.back.service.market.vo.AdminFundFlowSummaryResponse;
import stock.back.service.market.vo.AdminOrderFlowSummaryResponse;
import stock.back.service.market.vo.AdminRecentCashFlowResponse;
import stock.back.service.market.vo.AdminSymbolFlowResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;

final class AdminFlowResponseMapper {

    private AdminFlowResponseMapper() {
    }

    static AdminFundFlowSummaryResponse toFundFlowSummary(ResultSet rs) throws SQLException {
        BigDecimal totalCashBalance = rs.getBigDecimal("total_cash_balance");
        BigDecimal totalReservedBuyCash = rs.getBigDecimal("total_reserved_buy_cash");
        BigDecimal totalHoldingMarketValue = rs.getBigDecimal("total_holding_market_value");
        BigDecimal externalDepositAmount = rs.getBigDecimal("external_deposit_amount");
        BigDecimal externalWithdrawAmount = rs.getBigDecimal("external_withdraw_amount");
        BigDecimal dividendIncomeAmount = rs.getBigDecimal("dividend_income_amount");
        BigDecimal buyNetAmount = rs.getBigDecimal("buy_net_amount");
        BigDecimal sellNetAmount = rs.getBigDecimal("sell_net_amount");
        return new AdminFundFlowSummaryResponse(
                rs.getLong("active_account_count"),
                totalCashBalance,
                totalReservedBuyCash,
                totalHoldingMarketValue,
                totalCashBalance.add(totalReservedBuyCash).add(totalHoldingMarketValue),
                externalDepositAmount,
                externalWithdrawAmount,
                externalDepositAmount.subtract(externalWithdrawAmount),
                dividendIncomeAmount,
                buyNetAmount,
                sellNetAmount,
                sellNetAmount.subtract(buyNetAmount),
                rs.getBigDecimal("total_fee_amount"),
                rs.getBigDecimal("total_tax_amount"),
                rs.getBigDecimal("realized_profit"),
                rs.getLong("execution_count")
        );
    }

    static AdminOrderFlowSummaryResponse toOrderFlowSummary(ResultSet rs) throws SQLException {
        return new AdminOrderFlowSummaryResponse(
                rs.getLong("open_order_count"),
                rs.getLong("open_buy_order_count"),
                rs.getLong("open_sell_order_count"),
                rs.getLong("partially_filled_order_count"),
                rs.getBigDecimal("reserved_buy_cash"),
                rs.getLong("reserved_sell_quantity"),
                rs.getLong("today_order_count"),
                rs.getLong("today_filled_order_count"),
                rs.getLong("today_cancelled_order_count"),
                rs.getLong("today_rejected_order_count")
        );
    }

    static AdminCorporateActionFlowSummaryResponse toCorporateActionFlowSummary(ResultSet rs) throws SQLException {
        return new AdminCorporateActionFlowSummaryResponse(
                rs.getLong("announced_count"),
                rs.getLong("ex_rights_applied_count"),
                rs.getLong("paid_count"),
                rs.getLong("listed_count"),
                rs.getLong("delisted_count"),
                rs.getLong("pending_count"),
                rs.getLong("today_created_count")
        );
    }

    static AdminSymbolFlowResponse toSymbolFlow(ResultSet rs) throws SQLException {
        BigDecimal currentPrice = MarketQuerySupport.zeroIfNull(rs.getBigDecimal("current_price"));
        BigDecimal previousClose = MarketQuerySupport.zeroIfNull(rs.getBigDecimal("previous_close"));
        return new AdminSymbolFlowResponse(
                rs.getString("symbol"),
                rs.getString("name"),
                rs.getBoolean("enabled"),
                rs.getString("market_status"),
                rs.getLong("issued_shares"),
                rs.getLong("tradable_shares"),
                currentPrice,
                previousClose,
                calculateChangeRate(currentPrice, previousClose),
                rs.getLong("execution_count"),
                rs.getLong("execution_quantity"),
                MarketQuerySupport.zeroIfNull(rs.getBigDecimal("turnover_amount")),
                rs.getLong("buy_quantity"),
                rs.getLong("sell_quantity"),
                MarketQuerySupport.zeroIfNull(rs.getBigDecimal("buy_net_amount")),
                MarketQuerySupport.zeroIfNull(rs.getBigDecimal("sell_net_amount")),
                rs.getLong("open_order_count"),
                rs.getLong("open_buy_order_count"),
                rs.getLong("open_sell_order_count"),
                MarketQuerySupport.zeroIfNull(rs.getBigDecimal("reserved_buy_cash")),
                rs.getLong("holder_count"),
                rs.getLong("holding_quantity"),
                rs.getLong("pending_corporate_action_count"),
                MarketQuerySupport.toDateTime(rs.getTimestamp("last_executed_at"))
        );
    }

    static AdminRecentCashFlowResponse toRecentCashFlow(ResultSet rs) throws SQLException {
        return new AdminRecentCashFlowResponse(
                rs.getLong("id"),
                rs.getLong("account_id"),
                rs.getString("user_key"),
                rs.getString("flow_type"),
                MarketQuerySupport.zeroIfNull(rs.getBigDecimal("amount")),
                rs.getString("reason"),
                rs.getString("created_by"),
                rs.getTimestamp("created_at").toLocalDateTime()
        );
    }

    private static BigDecimal calculateChangeRate(BigDecimal currentPrice, BigDecimal previousClose) {
        if (currentPrice == null || previousClose == null || previousClose.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return currentPrice.subtract(previousClose)
                .multiply(BigDecimal.valueOf(100))
                .divide(previousClose, 4, RoundingMode.HALF_UP);
    }
}
