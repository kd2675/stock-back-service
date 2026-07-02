package stock.back.service.market.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import stock.back.service.common.exception.StockException;
import stock.back.service.market.vo.AutoParticipantCashFlowControlRequest;
import stock.back.service.market.vo.BatchJobRuntimeControlRequest;
import web.common.core.response.base.vo.Code;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class StockBatchAdminClientTest {

    private static final String BASE_URL = "http://stock-batch-test";

    @Test
    void createRequestFactory_usesPatchCapableJdkClient() {
        assertThat(StockBatchAdminClient.createRequestFactory(3000, 10000))
                .isInstanceOf(JdkClientHttpRequestFactory.class);
    }

    @Test
    void getAutoParticipantCashFlowStatus_sendsInternalTokenAndReturnsData() {
        ClientFixture fixture = clientFixture("secret-token");

        fixture.server().expect(requestTo(endpoint("/internal/stock-batch/v1/jobs/auto-participant-cash-flow/status")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Token", "secret-token"))
                .andRespond(withSuccess("""
                        {"success":true,"data":{"schedulerConfigured":true,"runtimeEnabled":false,"effectiveEnabled":false,"updatedBy":"admin-user","updatedAt":"2026-06-25T15:00:00"}}
                        """, MediaType.APPLICATION_JSON));

        var response = fixture.client().getAutoParticipantCashFlowStatus();

        assertThat(response.runtimeEnabled()).isFalse();
        assertThat(response.updatedBy()).isEqualTo("admin-user");
        fixture.server().verify();
    }

    @Test
    void updateAutoParticipantCashFlowStatus_sendsInternalTokenAndBody() {
        ClientFixture fixture = clientFixture("secret-token");

        fixture.server().expect(requestTo(endpoint("/internal/stock-batch/v1/jobs/auto-participant-cash-flow/status")))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(header("X-Internal-Token", "secret-token"))
                .andExpect(content().json("""
                        {"runtimeEnabled":true,"updatedBy":"admin-user"}
                        """))
                .andRespond(withSuccess("""
                        {"success":true,"data":{"schedulerConfigured":true,"runtimeEnabled":true,"effectiveEnabled":true,"updatedBy":"admin-user","updatedAt":"2026-06-25T15:00:00"}}
                        """, MediaType.APPLICATION_JSON));

        var response = fixture.client().updateAutoParticipantCashFlowStatus(
                new AutoParticipantCashFlowControlRequest(true, "admin-user")
        );

        assertThat(response.runtimeEnabled()).isTrue();
        assertThat(response.effectiveEnabled()).isTrue();
        fixture.server().verify();
    }

    @Test
    void runAutoParticipantCashFlow_sendsInternalTokenAndReturnsJobResponse() {
        ClientFixture fixture = clientFixture("secret-token");

        fixture.server().expect(requestTo(endpoint("/internal/stock-batch/v1/jobs/auto-participant-cash-flow/run")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Token", "secret-token"))
                .andRespond(withSuccess("""
                        {"success":true,"data":{"job":"auto-participant-cash-flow","status":"COMPLETED","executionMode":"recurring-cash","processedCount":3,"message":"Job completed","startedAt":"2026-06-25T15:00:00","completedAt":"2026-06-25T15:00:01"}}
                        """, MediaType.APPLICATION_JSON));

        var response = fixture.client().runAutoParticipantCashFlow();

        assertThat(response.job()).isEqualTo("auto-participant-cash-flow");
        assertThat(response.processedCount()).isEqualTo(3);
        fixture.server().verify();
    }

    @Test
    void runMarketCloseRollover_sendsInternalTokenAndReturnsJobResponse() {
        ClientFixture fixture = clientFixture("secret-token");

        fixture.server().expect(requestTo(endpoint("/internal/stock-batch/v1/jobs/market-close/rollover")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Token", "secret-token"))
                .andRespond(withSuccess("""
                        {"success":true,"data":{"job":"market-close-rollover","status":"COMPLETED","executionMode":"price-limit-base","processedCount":5,"message":"Job completed","startedAt":"2026-06-25T15:30:00","completedAt":"2026-06-25T15:30:01"}}
                        """, MediaType.APPLICATION_JSON));

        var response = fixture.client().runMarketCloseRollover();

        assertThat(response.job()).isEqualTo("market-close-rollover");
        assertThat(response.processedCount()).isEqualTo(5);
        fixture.server().verify();
    }

    @Test
    void runMarketCloseRollover_withSymbol_sendsSymbolInternalTokenAndReturnsJobResponse() {
        ClientFixture fixture = clientFixture("secret-token");

        fixture.server().expect(requestTo(endpoint("/internal/stock-batch/v1/jobs/market-close/rollover/MC001")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Token", "secret-token"))
                .andRespond(withSuccess("""
                        {"success":true,"data":{"job":"market-close-rollover","status":"COMPLETED","executionMode":"price-limit-base:MC001","processedCount":4,"message":"Job completed","startedAt":"2026-06-25T15:30:00","completedAt":"2026-06-25T15:30:01"}}
                        """, MediaType.APPLICATION_JSON));

        var response = fixture.client().runMarketCloseRollover("MC001");

        assertThat(response.job()).isEqualTo("market-close-rollover");
        assertThat(response.executionMode()).isEqualTo("price-limit-base:MC001");
        assertThat(response.processedCount()).isEqualTo(4);
        fixture.server().verify();
    }

    @Test
    void getBatchJobRuntimeControls_sendsInternalTokenAndReturnsRuntimeList() {
        ClientFixture fixture = clientFixture("secret-token");

        fixture.server().expect(requestTo(endpoint("/internal/stock-batch/v1/jobs/runtime-controls")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Token", "secret-token"))
                .andRespond(withSuccess("""
                        {"success":true,"data":[{"jobName":"auto-market","schedulerConfigured":true,"runtimeEnabled":false,"effectiveEnabled":false,"updatedBy":"admin-user","updatedAt":"2026-06-25T15:00:00"}]}
                        """, MediaType.APPLICATION_JSON));

        var response = fixture.client().getBatchJobRuntimeControls();

        assertThat(response).hasSize(1);
        assertThat(response.getFirst().jobName()).isEqualTo("auto-market");
        assertThat(response.getFirst().runtimeEnabled()).isFalse();
        assertThat(response.getFirst().effectiveEnabled()).isFalse();
        fixture.server().verify();
    }

    @Test
    void updateBatchJobRuntimeControl_sendsInternalTokenAndBody() {
        ClientFixture fixture = clientFixture("secret-token");

        fixture.server().expect(requestTo(endpoint("/internal/stock-batch/v1/jobs/runtime-controls/auto-market")))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(header("X-Internal-Token", "secret-token"))
                .andExpect(content().json("""
                        {"runtimeEnabled":false,"updatedBy":"admin-user"}
                        """))
                .andRespond(withSuccess("""
                        {"success":true,"data":{"jobName":"auto-market","schedulerConfigured":true,"runtimeEnabled":false,"effectiveEnabled":false,"updatedBy":"admin-user","updatedAt":"2026-06-25T15:00:00"}}
                        """, MediaType.APPLICATION_JSON));

        var response = fixture.client().updateBatchJobRuntimeControl(
                "auto-market",
                new BatchJobRuntimeControlRequest(false, "admin-user")
        );

        assertThat(response.jobName()).isEqualTo("auto-market");
        assertThat(response.runtimeEnabled()).isFalse();
        assertThat(response.updatedBy()).isEqualTo("admin-user");
        fixture.server().verify();
    }

    @Test
    void updateBatchJobRuntimeControl_unknownJob_preservesNotFoundBoundary() {
        ClientFixture fixture = clientFixture("secret-token");

        fixture.server().expect(requestTo(endpoint("/internal/stock-batch/v1/jobs/runtime-controls/not-a-job")))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(header("X-Internal-Token", "secret-token"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"success":false,"code":4040000,"message":"Unknown batch job"}
                                """));

        assertThatThrownBy(() -> fixture.client().updateBatchJobRuntimeControl(
                "not-a-job",
                new BatchJobRuntimeControlRequest(false, "admin-user")
        ))
                .isInstanceOf(StockException.class)
                .extracting("errorCode")
                .isEqualTo(Code.NOT_FOUND);

        fixture.server().verify();
    }

    @Test
    void updateBatchJobRuntimeControl_badRequest_preservesBadRequestBoundary() {
        ClientFixture fixture = clientFixture("secret-token");

        fixture.server().expect(requestTo(endpoint("/internal/stock-batch/v1/jobs/runtime-controls/%20")))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(header("X-Internal-Token", "secret-token"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"success":false,"code":4000000,"message":"jobName is required"}
                                """));

        assertThatThrownBy(() -> fixture.client().updateBatchJobRuntimeControl(
                " ",
                new BatchJobRuntimeControlRequest(false, "admin-user")
        ))
                .isInstanceOf(StockException.class)
                .extracting("errorCode")
                .isEqualTo(Code.BAD_REQUEST);

        fixture.server().verify();
    }

    @Test
    void getBatchJobRuntimeControls_internalTokenFailure_isGatewayProblemForStockBack() {
        ClientFixture fixture = clientFixture("wrong-token");

        fixture.server().expect(requestTo(endpoint("/internal/stock-batch/v1/jobs/runtime-controls")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Token", "wrong-token"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"success":false,"code":401,"message":"Unauthorized internal batch request"}
                                """));

        assertThatThrownBy(fixture.client()::getBatchJobRuntimeControls)
                .isInstanceOf(StockException.class)
                .extracting("errorCode")
                .isEqualTo(Code.BAD_GATEWAY);

        fixture.server().verify();
    }

    @Test
    void getBatchJobRuntimeControls_failedSuccessWrapper_isGatewayProblemEvenWhenHttpStatusIsOk() {
        ClientFixture fixture = clientFixture("secret-token");

        fixture.server().expect(requestTo(endpoint("/internal/stock-batch/v1/jobs/runtime-controls")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Token", "secret-token"))
                .andRespond(withSuccess("""
                        {"success":false,"code":5000000,"message":"Batch control failed","data":[{"jobName":"auto-market","schedulerConfigured":true,"runtimeEnabled":true,"effectiveEnabled":true}]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(fixture.client()::getBatchJobRuntimeControls)
                .isInstanceOf(StockException.class)
                .extracting("errorCode")
                .isEqualTo(Code.BAD_GATEWAY);

        fixture.server().verify();
    }

    private static ClientFixture clientFixture(String internalToken) {
        RestClient.Builder restClientBuilder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        StockBatchAdminClient client = new StockBatchAdminClient(restClientBuilder.build(), internalToken);
        return new ClientFixture(server, client);
    }

    private static String endpoint(String path) {
        return BASE_URL + path;
    }

    private record ClientFixture(
            MockRestServiceServer server,
            StockBatchAdminClient client
    ) {
    }
}
