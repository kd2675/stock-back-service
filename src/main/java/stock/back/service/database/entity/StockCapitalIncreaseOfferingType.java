package stock.back.service.database.entity;

public enum StockCapitalIncreaseOfferingType {
    SHAREHOLDER_ALLOCATION,
    PUBLIC_OFFERING;

    public static StockCapitalIncreaseOfferingType defaultType() {
        return SHAREHOLDER_ALLOCATION;
    }
}
