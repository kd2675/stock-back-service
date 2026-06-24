package stock.back.service.market.vo;

public record AutoParticipantRequest(
        String displayName,
        Boolean enabled,
        String profileType
) {
}
