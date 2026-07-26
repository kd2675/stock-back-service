package stock.back.service.market.vo;

import java.math.BigDecimal;
import java.util.List;

public record SystemCustodyOverviewResponse(
        int recommendedWithdrawalCustodyAccountCount,
        long currentWithdrawalCustodyAccountCount,
        int recommendedIssuanceCustodyAccountsPerSymbol,
        long roleSeparatedIssueSymbolCount,
        long recommendedIssuanceCustodyAccountCount,
        long currentIssuanceCustodyAccountCount,
        List<Account> accounts
) {

    public SystemCustodyOverviewResponse {
        accounts = accounts == null ? List.of() : List.copyOf(accounts);
    }

    public record Account(
            long accountId,
            String accountCode,
            String userKey,
            String accountStatus,
            String selfTradeGroupId,
            String deskCode,
            String mappingStatus,
            BigDecimal cashBalance,
            List<Holding> holdings
    ) {

        public Account {
            holdings = holdings == null ? List.of() : List.copyOf(holdings);
        }
    }

    public record Holding(
            String symbol,
            long quantity,
            long reservedQuantity,
            BigDecimal averagePrice,
            BigDecimal currentPrice,
            BigDecimal marketValue
    ) {
    }
}
