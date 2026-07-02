package stock.back.service.market.biz;

import stock.back.service.market.vo.AutoParticipantHoldingGroupResponse;
import stock.back.service.market.vo.AutoParticipantHoldingResponse;
import stock.back.service.market.vo.AutoParticipantOverviewResponse;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class AutoParticipantOverviewResponseMapper {

    private AutoParticipantOverviewResponseMapper() {
    }

    static AutoParticipantHoldingResponse toHolding(ResultSet rs) throws SQLException {
        return new AutoParticipantHoldingResponse(
                rs.getString("symbol"),
                rs.getLong("quantity"),
                rs.getLong("reserved_quantity"),
                rs.getLong("available_quantity"),
                MarketQuerySupport.zeroIfNull(rs.getBigDecimal("average_price")),
                MarketQuerySupport.zeroIfNull(rs.getBigDecimal("current_price")),
                MarketQuerySupport.zeroIfNull(rs.getBigDecimal("market_value")),
                MarketQuerySupport.zeroIfNull(rs.getBigDecimal("unrealized_profit"))
        );
    }

    static List<AutoParticipantOverviewResponse> withHoldingsByAccountId(
            List<AutoParticipantOverviewResponse> overviews,
            Map<Long, List<AutoParticipantHoldingResponse>> holdingsByAccountId
    ) {
        return overviews.stream()
                .map(overview -> overview.withHoldings(
                        overview.accountId() == null ? List.of() : holdingsByAccountId.getOrDefault(overview.accountId(), List.of())
                ))
                .toList();
    }

    static List<AutoParticipantHoldingGroupResponse> toHoldingGroups(ResultSet rs) throws SQLException {
        List<AutoParticipantHoldingGroupResponse> responses = new ArrayList<>();
        String currentUserKey = null;
        Long currentAccountId = null;
        List<AutoParticipantHoldingResponse> currentHoldings = new ArrayList<>();
        while (rs.next()) {
            String userKey = rs.getString("user_key");
            if (!userKey.equals(currentUserKey)) {
                if (currentUserKey != null) {
                    responses.add(new AutoParticipantHoldingGroupResponse(currentUserKey, currentAccountId, currentHoldings));
                }
                currentUserKey = userKey;
                currentAccountId = rs.getObject("account_id", Long.class);
                currentHoldings = new ArrayList<>();
            }
            String symbol = rs.getString("symbol");
            if (symbol != null) {
                currentHoldings.add(toHolding(rs));
            }
        }
        if (currentUserKey != null) {
            responses.add(new AutoParticipantHoldingGroupResponse(currentUserKey, currentAccountId, currentHoldings));
        }
        return responses;
    }

}
