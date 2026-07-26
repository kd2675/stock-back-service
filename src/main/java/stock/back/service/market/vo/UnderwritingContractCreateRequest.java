package stock.back.service.market.vo;

public record UnderwritingContractCreateRequest(
        String underwritingType,
        String changeReason
) {
}
