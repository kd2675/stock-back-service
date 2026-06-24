package stock.back.service.market.vo;

public record AutoParticipantSymbolConfigRequest(
        Boolean enabled,
        Integer intensity
) {
}
