package stock.back.service.market.vo;

public record InstitutionPilotActivationRequest(
        String symbol,
        String changeReason
) {
}
