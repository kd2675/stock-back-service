package stock.back.service.common.exception;

import web.common.core.response.base.exception.GeneralException;
import web.common.core.response.base.vo.Code;

public class StockException extends GeneralException {

    public StockException(Code errorCode, String message) {
        super(errorCode, message);
    }

    public static StockException unauthorized(String message) {
        return new StockException(Code.UNAUTHORIZED, message);
    }

    public static StockException badRequest(String message) {
        return new StockException(Code.BAD_REQUEST, message);
    }

    public static StockException notFound(String message) {
        return new StockException(Code.NOT_FOUND, message);
    }

    public static StockException conflict(String message) {
        return new StockException(Code.CONFLICT, message);
    }
}
