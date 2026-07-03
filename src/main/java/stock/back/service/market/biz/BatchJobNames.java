package stock.back.service.market.biz;

import org.springframework.util.StringUtils;

final class BatchJobNames {

    static final String MARKET_DATA_REFRESH = "market-data-refresh";
    static final String VIRTUAL_PRICE_EXECUTION = "virtual-price-execution";
    static final String ORDER_BOOK_EXECUTION = "order-book-execution";
    static final String CORPORATE_ACTIONS = "corporate-actions";
    static final String AUTO_MARKET = "auto-market";
    static final String AUTO_MARKET_ORDER_EXPIRY = "auto-market-order-expiry";
    static final String LISTING_AUTO_MARKET = "listing-auto-market";
    static final String AUTO_PARTICIPANT_CASH_FLOW = "auto-participant-cash-flow";
    static final String MARKET_CLOSE_ROLLOVER = "market-close-rollover";
    static final String PORTFOLIO_SETTLEMENT = "portfolio-settlement";
    static final String HOLDING_CLEANUP = "holding-cleanup";

    private BatchJobNames() {
    }

    static String normalize(String jobName) {
        if (!StringUtils.hasText(jobName)) {
            throw new IllegalArgumentException("jobName is required");
        }
        return jobName.trim();
    }
}
