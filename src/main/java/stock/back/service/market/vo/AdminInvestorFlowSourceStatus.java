package stock.back.service.market.vo;

public enum AdminInvestorFlowSourceStatus {
    LIVE_ASYNC,
    CLOSED_SNAPSHOT,
    NO_TRADING,
    EOD_PENDING,
    EOD_FAILED,
    MISSING
}
