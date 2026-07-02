package stock.back.service.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import web.common.core.response.base.vo.Code;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler globalExceptionHandler = new GlobalExceptionHandler();

    @Test
    void handleDisconnectedClient_asyncRequestNotUsableException_doesNotCreateErrorResponse() {
        AsyncRequestNotUsableException exception = new AsyncRequestNotUsableException(
                "Servlet container error notification for disconnected client"
        );

        assertThatCode(() -> globalExceptionHandler.handleDisconnectedClient(exception))
                .doesNotThrowAnyException();
    }

    @Test
    void handleQueryTimeoutException_returnsDataAccessErrorResponse() {
        QueryTimeoutException exception = new QueryTimeoutException("Statement cancelled due to timeout");

        var response = globalExceptionHandler.handleQueryTimeoutException(exception);

        assertThat(response.getStatusCode()).isEqualTo(Code.DATA_ACCESS_ERROR.getHttpStatus());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(Code.DATA_ACCESS_ERROR.getCode());
    }
}
