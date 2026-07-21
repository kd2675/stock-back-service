package stock.back.service.market.vo;

public enum SimulationClockJumpAction {
    TODAY_MARKET_CLOSE,
    NEXT_SIMULATION_DAY_START,
    NEXT_PREOPEN_TRANSFORM_START,
    NEXT_AUTO_MARKET_PREPARATION_START,
    NEXT_MARKET_OPEN
}
