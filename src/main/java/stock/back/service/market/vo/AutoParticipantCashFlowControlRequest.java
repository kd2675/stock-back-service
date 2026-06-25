package stock.back.service.market.vo;

public record AutoParticipantCashFlowControlRequest(
        Boolean runtimeEnabled,
        String updatedBy
) {
}
