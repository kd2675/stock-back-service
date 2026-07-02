package stock.back.service.market.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import stock.back.service.common.exception.StockException;
import stock.back.service.market.vo.AutoParticipantCashFlowControlRequest;
import stock.back.service.market.vo.AutoParticipantCashFlowStatusResponse;
import stock.back.service.market.vo.BatchJobRuntimeControlRequest;
import stock.back.service.market.vo.BatchJobRuntimeStatusResponse;
import stock.back.service.market.vo.StockBatchJobRunResponse;
import web.common.core.response.base.dto.ResponseDataDTO;
import web.common.core.response.base.vo.Code;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

@Slf4j
@Component
public class StockBatchAdminClient {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    private static final String CASH_FLOW_STATUS_PATH = "/internal/stock-batch/v1/jobs/auto-participant-cash-flow/status";
    private static final String CASH_FLOW_RUN_PATH = "/internal/stock-batch/v1/jobs/auto-participant-cash-flow/run";
    private static final String MARKET_CLOSE_ROLLOVER_RUN_PATH = "/internal/stock-batch/v1/jobs/market-close/rollover";
    private static final String RUNTIME_CONTROLS_PATH = "/internal/stock-batch/v1/jobs/runtime-controls";
    private static final ParameterizedTypeReference<ResponseDataDTO<AutoParticipantCashFlowStatusResponse>>
            CASH_FLOW_STATUS_RESPONSE_TYPE = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ResponseDataDTO<StockBatchJobRunResponse>>
            JOB_RUN_RESPONSE_TYPE = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ResponseDataDTO<List<BatchJobRuntimeStatusResponse>>>
            RUNTIME_STATUS_LIST_RESPONSE_TYPE = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ResponseDataDTO<BatchJobRuntimeStatusResponse>>
            RUNTIME_STATUS_RESPONSE_TYPE = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;
    private final String internalToken;

    @Autowired
    public StockBatchAdminClient(
            @Value("${stock.batch-client.base-url:http://localhost:20481}") String baseUrl,
            @Value("${stock.batch-client.internal-token}") String internalToken,
            @Value("${stock.batch-client.connect-timeout-ms:3000}") long connectTimeoutMs,
            @Value("${stock.batch-client.read-timeout-ms:10000}") long readTimeoutMs
    ) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(createRequestFactory(connectTimeoutMs, readTimeoutMs))
                .build();
        this.internalToken = internalToken;
    }

    StockBatchAdminClient(RestClient restClient, String internalToken) {
        this.restClient = restClient;
        this.internalToken = internalToken;
    }

    static ClientHttpRequestFactory createRequestFactory(long connectTimeoutMs, long readTimeoutMs) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return requestFactory;
    }

    public AutoParticipantCashFlowStatusResponse getAutoParticipantCashFlowStatus() {
        return getBatchApi(
                "월급 지급 배치 상태를 조회하지 못했습니다.",
                CASH_FLOW_STATUS_PATH,
                CASH_FLOW_STATUS_RESPONSE_TYPE
        );
    }

    public AutoParticipantCashFlowStatusResponse updateAutoParticipantCashFlowStatus(
            AutoParticipantCashFlowControlRequest request
    ) {
        return patchBatchApi(
                "월급 지급 배치 상태를 변경하지 못했습니다.",
                CASH_FLOW_STATUS_PATH,
                request,
                CASH_FLOW_STATUS_RESPONSE_TYPE
        );
    }

    public StockBatchJobRunResponse runAutoParticipantCashFlow() {
        return postBatchApi(
                "월급 지급 배치를 실행하지 못했습니다.",
                CASH_FLOW_RUN_PATH,
                JOB_RUN_RESPONSE_TYPE
        );
    }

    public StockBatchJobRunResponse runMarketCloseRollover() {
        return postBatchApi(
                "장마감 롤오버 배치를 실행하지 못했습니다.",
                MARKET_CLOSE_ROLLOVER_RUN_PATH,
                JOB_RUN_RESPONSE_TYPE
        );
    }

    public StockBatchJobRunResponse runMarketCloseRollover(String symbol) {
        return postBatchApi(
                "종목 장마감 롤오버 배치를 실행하지 못했습니다.",
                MARKET_CLOSE_ROLLOVER_RUN_PATH + "/{symbol}",
                symbol,
                JOB_RUN_RESPONSE_TYPE
        );
    }

    public List<BatchJobRuntimeStatusResponse> getBatchJobRuntimeControls() {
        return getBatchApi(
                "배치 자동 실행 상태를 조회하지 못했습니다.",
                RUNTIME_CONTROLS_PATH,
                RUNTIME_STATUS_LIST_RESPONSE_TYPE
        );
    }

    public BatchJobRuntimeStatusResponse updateBatchJobRuntimeControl(
            String jobName,
            BatchJobRuntimeControlRequest request
    ) {
        return patchBatchApi(
                "배치 자동 실행 상태를 변경하지 못했습니다.",
                RUNTIME_CONTROLS_PATH + "/{jobName}",
                jobName,
                request,
                RUNTIME_STATUS_RESPONSE_TYPE
        );
    }

    private <T> T getBatchApi(
            String message,
            String path,
            ParameterizedTypeReference<ResponseDataDTO<T>> responseType
    ) {
        return invokeBatchApi(
                message,
                () -> restClient.get()
                        .uri(path)
                        .headers(this::applyInternalHeaders)
                        .retrieve()
                        .body(responseType)
        );
    }

    private <T> T postBatchApi(
            String message,
            String path,
            ParameterizedTypeReference<ResponseDataDTO<T>> responseType
    ) {
        return invokeBatchApi(
                message,
                () -> restClient.post()
                        .uri(path)
                        .headers(this::applyInternalHeaders)
                        .retrieve()
                        .body(responseType)
        );
    }

    private <T> T postBatchApi(
            String message,
            String path,
            Object uriVariable,
            ParameterizedTypeReference<ResponseDataDTO<T>> responseType
    ) {
        return invokeBatchApi(
                message,
                () -> restClient.post()
                        .uri(path, uriVariable)
                        .headers(this::applyInternalHeaders)
                        .retrieve()
                        .body(responseType)
        );
    }

    private <T> T patchBatchApi(
            String message,
            String path,
            Object request,
            ParameterizedTypeReference<ResponseDataDTO<T>> responseType
    ) {
        return invokeBatchApi(
                message,
                () -> restClient.patch()
                        .uri(path)
                        .headers(this::applyInternalHeaders)
                        .body(request)
                        .retrieve()
                        .body(responseType)
        );
    }

    private <T> T patchBatchApi(
            String message,
            String path,
            Object uriVariable,
            Object request,
            ParameterizedTypeReference<ResponseDataDTO<T>> responseType
    ) {
        return invokeBatchApi(
                message,
                () -> restClient.patch()
                        .uri(path, uriVariable)
                        .headers(this::applyInternalHeaders)
                        .body(request)
                        .retrieve()
                        .body(responseType)
        );
    }

    private void applyInternalHeaders(HttpHeaders headers) {
        if (StringUtils.hasText(internalToken)) {
            headers.set(INTERNAL_TOKEN_HEADER, internalToken);
        }
    }

    private <T> T requireData(ResponseDataDTO<T> response) {
        if (response == null) {
            throw new StockException(Code.BAD_GATEWAY, "Batch API returned empty response");
        }
        if (!Boolean.TRUE.equals(response.getSuccess())) {
            throw new StockException(Code.BAD_GATEWAY, "Batch API returned failed response");
        }
        if (response.getData() == null) {
            throw new StockException(Code.BAD_GATEWAY, "Batch API returned empty response");
        }
        return response.getData();
    }

    private <T> T invokeBatchApi(String message, Supplier<ResponseDataDTO<T>> responseSupplier) {
        try {
            return requireData(responseSupplier.get());
        } catch (RestClientResponseException ex) {
            throw batchGatewayException(message, ex);
        } catch (RestClientException ex) {
            throw batchGatewayException(message, ex);
        }
    }

    private StockException batchGatewayException(String message, RestClientException ex) {
        log.warn("{} reason={}", message, ex.getMessage());
        return new StockException(Code.BAD_GATEWAY, message);
    }

    private StockException batchGatewayException(String message, RestClientResponseException ex) {
        Code code = switch (ex.getStatusCode().value()) {
            case 400 -> Code.BAD_REQUEST;
            case 404 -> Code.NOT_FOUND;
            case 409 -> Code.CONFLICT;
            default -> Code.BAD_GATEWAY;
        };
        log.warn("{} status={} reason={}", message, ex.getStatusCode(), ex.getMessage());
        return new StockException(code, message);
    }
}
