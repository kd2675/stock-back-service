package stock.back.service.trading.vo;

public record AccountStatusResponse(
        boolean hasAccount,
        AccountResponse account
) {
    public static AccountStatusResponse connected(AccountResponse account) {
        return new AccountStatusResponse(true, account);
    }

    public static AccountStatusResponse missing() {
        return new AccountStatusResponse(false, null);
    }
}
