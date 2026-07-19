package stock.back.service.market.vo;

public record AutoMarketRegimeCountWeightsResponse(
        int oneTime,
        int twoTimes,
        int threeTimes,
        int fourTimes
) {
}
