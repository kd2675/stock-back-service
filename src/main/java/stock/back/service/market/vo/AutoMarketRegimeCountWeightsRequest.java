package stock.back.service.market.vo;

public record AutoMarketRegimeCountWeightsRequest(
        Integer oneTime,
        Integer twoTimes,
        Integer threeTimes,
        Integer fourTimes
) {
}
