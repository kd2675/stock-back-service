package stock.back.service.database.entity;

public enum StockAccountParticipantCategory {
    MANUAL_PARTICIPANT,
    AUTO_PARTICIPANT,
    INSTITUTIONAL_INVESTOR,
    LIQUIDITY_PROVIDER,
    ISSUE_UNDERWRITER,
    SYSTEM_CUSTODY;

    public boolean isPortfolioSettlementTarget() {
        return this == MANUAL_PARTICIPANT
                || this == AUTO_PARTICIPANT
                || this == INSTITUTIONAL_INVESTOR;
    }

    public boolean canSubmitUserOrders() {
        return this == MANUAL_PARTICIPANT;
    }
}
