package stock.back.service.market.vo;

public record BatchJobRuntimeControlRequest(
        Boolean runtimeEnabled,
        String updatedBy
) {
}
