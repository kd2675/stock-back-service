package stock.back.service.market.vo;

public record AutoParticipantV3RuntimeRequest(
        boolean runtimeEnabled,
        String changeReason
) {
}
