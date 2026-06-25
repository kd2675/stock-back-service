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

@Slf4j
@Component
public class StockBatchAdminClient {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    private static final String CASH_FLOW_STATUS_PATH = "/internal/stock-batch/v1/jobs/auto-participant-cash-flow/status";
    private static final String CASH_FLOW_RUN_PATH = "/internal/stock-batch/v1/jobs/auto-participant-cash-flow/run";
    private static final String RUNTIME_CONTROLS_PATH = "/internal/stock-batch/v1/jobs/runtime-controls";

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
        try {
            ResponseDataDTO<AutoParticipantCashFlowStatusResponse> response = restClient.get()
                    .uri(CASH_FLOW_STATUS_PATH)
                    .headers(this::applyInternalHeaders)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return requireData(response);
        } catch (RestClientResponseException ex) {
            throw batchGatewayException("월급 지급 배치 상태를 조회하지 못했습니다.", ex);
        } catch (RestClientException ex) {
            throw batchGatewayException("월급 지급 배치 상태를 조회하지 못했습니다.", ex);
        }
    }

    public AutoParticipantCashFlowStatusResponse updateAutoParticipantCashFlowStatus(
            AutoParticipantCashFlowControlRequest request
    ) {
        try {
            ResponseDataDTO<AutoParticipantCashFlowStatusResponse> response = restClient.patch()
                    .uri(CASH_FLOW_STATUS_PATH)
                    .headers(this::applyInternalHeaders)
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return requireData(response);
        } catch (RestClientResponseException ex) {
            throw batchGatewayException("월급 지급 배치 상태를 변경하지 못했습니다.", ex);
        } catch (RestClientException ex) {
            throw batchGatewayException("월급 지급 배치 상태를 변경하지 못했습니다.", ex);
        }
    }

    public StockBatchJobRunResponse runAutoParticipantCashFlow() {
        try {
            ResponseDataDTO<StockBatchJobRunResponse> response = restClient.post()
                    .uri(CASH_FLOW_RUN_PATH)
                    .headers(this::applyInternalHeaders)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return requireData(response);
        } catch (RestClientResponseException ex) {
            throw batchGatewayException("월급 지급 배치를 실행하지 못했습니다.", ex);
        } catch (RestClientException ex) {
            throw batchGatewayException("월급 지급 배치를 실행하지 못했습니다.", ex);
        }
    }

    public List<BatchJobRuntimeStatusResponse> getBatchJobRuntimeControls() {
        try {
            ResponseDataDTO<List<BatchJobRuntimeStatusResponse>> response = restClient.get()
                    .uri(RUNTIME_CONTROLS_PATH)
                    .headers(this::applyInternalHeaders)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return requireData(response);
        } catch (RestClientResponseException ex) {
            throw batchGatewayException("배치 자동 실행 상태를 조회하지 못했습니다.", ex);
        } catch (RestClientException ex) {
            throw batchGatewayException("배치 자동 실행 상태를 조회하지 못했습니다.", ex);
        }
    }

    public BatchJobRuntimeStatusResponse updateBatchJobRuntimeControl(
            String jobName,
            BatchJobRuntimeControlRequest request
    ) {
        try {
            ResponseDataDTO<BatchJobRuntimeStatusResponse> response = restClient.patch()
                    .uri(RUNTIME_CONTROLS_PATH + "/{jobName}", jobName)
                    .headers(this::applyInternalHeaders)
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return requireData(response);
        } catch (RestClientResponseException ex) {
            throw batchGatewayException("배치 자동 실행 상태를 변경하지 못했습니다.", ex);
        } catch (RestClientException ex) {
            throw batchGatewayException("배치 자동 실행 상태를 변경하지 못했습니다.", ex);
        }
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
