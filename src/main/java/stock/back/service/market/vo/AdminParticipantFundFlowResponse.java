package stock.back.service.market.vo;

public record AdminParticipantFundFlowResponse(
        AdminParticipantCategory participantCategory,
        AdminFundFlowSummaryResponse summary
) {
}
