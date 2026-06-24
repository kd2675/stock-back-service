package stock.back.service.trading.vo;

public record AccountReconnectRequest(
        String accountCode,
        String recoveryCode
) {
}
