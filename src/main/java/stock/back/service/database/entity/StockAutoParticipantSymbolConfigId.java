package stock.back.service.database.entity;

import java.io.Serializable;
import java.util.Objects;

public class StockAutoParticipantSymbolConfigId implements Serializable {

    private String userKey;
    private String symbol;

    public StockAutoParticipantSymbolConfigId() {
    }

    public StockAutoParticipantSymbolConfigId(String userKey, String symbol) {
        this.userKey = userKey;
        this.symbol = symbol;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof StockAutoParticipantSymbolConfigId that)) {
            return false;
        }
        return Objects.equals(userKey, that.userKey) && Objects.equals(symbol, that.symbol);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userKey, symbol);
    }
}
