package stock.back.service.market.client;

import org.junit.jupiter.api.Test;
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

    @Test
    void createRequestFactory_usesPatchCapableJdkClient() {
        assertThat(StockBatchAdminClient.createRequestFactory(3000, 10000))
                .isInstanceOf(JdkClientHttpRequestFactory.class);
    }

    @Test
    void getAutoParticipantCashFlowStatus_sendsInternalTokenAndReturnsData() {
        RestClient.Builder restClientBuilder = RestClient.builder().baseUrl("http://stock-batch-test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        StockBatchAdminClient client = new StockBatchAdminClient(restClientBuilder.build(), "secret-token");

        server.expect(requestTo("http://stock-batch-test/internal/stock-batch/v1/jobs/auto-participant-cash-flow/status"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("X-Internal-Token", "secret-token"))
                .andRespond(withSuccess("""
                        {"success":true,"data":{"schedulerConfigured":true,"runtimeEnabled":false,"effectiveEnabled":false,"updatedBy":"admin-user","updatedAt":"2026-06-25T15:00:00"}}
                        """, MediaType.APPLICATION_JSON));

        var response = client.getAutoParticipantCashFlowStatus();

        assertThat(response.runtimeEnabled()).isFalse();
        assertThat(response.updatedBy()).isEqualTo("admin-user");
        server.verify();
    }

    @Test
    void updateAutoParticipantCashFlowStatus_sendsInternalTokenAndBody() {
        RestClient.Builder restClientBuilder = RestClient.builder().baseUrl("http://stock-batch-test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        StockBatchAdminClient client = new StockBatchAdminClient(restClientBuilder.build(), "secret-token");

        server.expect(requestTo("http://stock-batch-test/internal/stock-batch/v1/jobs/auto-participant-cash-flow/status"))
                .andExpect(method(org.springframework.http.HttpMethod.PATCH))
                .andExpect(header("X-Internal-Token", "secret-token"))
                .andExpect(content().json("""
                        {"runtimeEnabled":true,"updatedBy":"admin-user"}
                        """))
                .andRespond(withSuccess("""
                        {"success":true,"data":{"schedulerConfigured":true,"runtimeEnabled":true,"effectiveEnabled":true,"updatedBy":"admin-user","updatedAt":"2026-06-25T15:00:00"}}
                        """, MediaType.APPLICATION_JSON));

        var response = client.updateAutoParticipantCashFlowStatus(
                new AutoParticipantCashFlowControlRequest(true, "admin-user")
        );

        assertThat(response.runtimeEnabled()).isTrue();
        assertThat(response.effectiveEnabled()).isTrue();
        server.verify();
    }

    @Test
    void runAutoParticipantCashFlow_sendsInternalTokenAndReturnsJobResponse() {
        RestClient.Builder restClientBuilder = RestClient.builder().baseUrl("http://stock-batch-test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        StockBatchAdminClient client = new StockBatchAdminClient(restClientBuilder.build(), "secret-token");

        server.expect(requestTo("http://stock-batch-test/internal/stock-batch/v1/jobs/auto-participant-cash-flow/run"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("X-Internal-Token", "secret-token"))
                .andRespond(withSuccess("""
                        {"success":true,"data":{"job":"auto-participant-cash-flow","status":"COMPLETED","executionMode":"recurring-cash","processedCount":3,"message":"Job completed","startedAt":"2026-06-25T15:00:00","completedAt":"2026-06-25T15:00:01"}}
                        """, MediaType.APPLICATION_JSON));

        var response = client.runAutoParticipantCashFlow();

        assertThat(response.job()).isEqualTo("auto-participant-cash-flow");
        assertThat(response.processedCount()).isEqualTo(3);
        server.verify();
    }

    @Test
    void runMarketCloseRollover_sendsInternalTokenAndReturnsJobResponse() {
        RestClient.Builder restClientBuilder = RestClient.builder().baseUrl("http://stock-batch-test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        StockBatchAdminClient client = new StockBatchAdminClient(restClientBuilder.build(), "secret-token");

        server.expect(requestTo("http://stock-batch-test/internal/stock-batch/v1/jobs/market-close/rollover"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("X-Internal-Token", "secret-token"))
                .andRespond(withSuccess("""
                        {"success":true,"data":{"job":"market-close-rollover","status":"COMPLETED","executionMode":"price-limit-base","processedCount":5,"message":"Job completed","startedAt":"2026-06-25T15:30:00","completedAt":"2026-06-25T15:30:01"}}
                        """, MediaType.APPLICATION_JSON));

        var response = client.runMarketCloseRollover();

        assertThat(response.job()).isEqualTo("market-close-rollover");
        assertThat(response.processedCount()).isEqualTo(5);
        server.verify();
    }

    @Test
    void runMarketCloseRollover_withSymbol_sendsSymbolInternalTokenAndReturnsJobResponse() {
        RestClient.Builder restClientBuilder = RestClient.builder().baseUrl("http://stock-batch-test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        StockBatchAdminClient client = new StockBatchAdminClient(restClientBuilder.build(), "secret-token");

        server.expect(requestTo("http://stock-batch-test/internal/stock-batch/v1/jobs/market-close/rollover/MC001"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("X-Internal-Token", "secret-token"))
                .andRespond(withSuccess("""
                        {"success":true,"data":{"job":"market-close-rollover","status":"COMPLETED","executionMode":"price-limit-base:MC001","processedCount":4,"message":"Job completed","startedAt":"2026-06-25T15:30:00","completedAt":"2026-06-25T15:30:01"}}
                        """, MediaType.APPLICATION_JSON));

        var response = client.runMarketCloseRollover("MC001");

        assertThat(response.job()).isEqualTo("market-close-rollover");
        assertThat(response.executionMode()).isEqualTo("price-limit-base:MC001");
        assertThat(response.processedCount()).isEqualTo(4);
        server.verify();
    }

    @Test
    void getBatchJobRuntimeControls_sendsInternalTokenAndReturnsRuntimeList() {
        RestClient.Builder restClientBuilder = RestClient.builder().baseUrl("http://stock-batch-test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        StockBatchAdminClient client = new StockBatchAdminClient(restClientBuilder.build(), "secret-token");

        server.expect(requestTo("http://stock-batch-test/internal/stock-batch/v1/jobs/runtime-controls"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("X-Internal-Token", "secret-token"))
                .andRespond(withSuccess("""
                        {"success":true,"data":[{"jobName":"auto-market","schedulerConfigured":true,"runtimeEnabled":false,"effectiveEnabled":false,"updatedBy":"admin-user","updatedAt":"2026-06-25T15:00:00"}]}
                        """, MediaType.APPLICATION_JSON));

        var response = client.getBatchJobRuntimeControls();

        assertThat(response).hasSize(1);
        assertThat(response.getFirst().jobName()).isEqualTo("auto-market");
        assertThat(response.getFirst().runtimeEnabled()).isFalse();
        assertThat(response.getFirst().effectiveEnabled()).isFalse();
        server.verify();
    }

    @Test
    void updateBatchJobRuntimeControl_sendsInternalTokenAndBody() {
        RestClient.Builder restClientBuilder = RestClient.builder().baseUrl("http://stock-batch-test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        StockBatchAdminClient client = new StockBatchAdminClient(restClientBuilder.build(), "secret-token");

        server.expect(requestTo("http://stock-batch-test/internal/stock-batch/v1/jobs/runtime-controls/auto-market"))
                .andExpect(method(org.springframework.http.HttpMethod.PATCH))
                .andExpect(header("X-Internal-Token", "secret-token"))
                .andExpect(content().json("""
                        {"runtimeEnabled":false,"updatedBy":"admin-user"}
                        """))
                .andRespond(withSuccess("""
                        {"success":true,"data":{"jobName":"auto-market","schedulerConfigured":true,"runtimeEnabled":false,"effectiveEnabled":false,"updatedBy":"admin-user","updatedAt":"2026-06-25T15:00:00"}}
                        """, MediaType.APPLICATION_JSON));

        var response = client.updateBatchJobRuntimeControl(
                "auto-market",
                new BatchJobRuntimeControlRequest(false, "admin-user")
        );

        assertThat(response.jobName()).isEqualTo("auto-market");
        assertThat(response.runtimeEnabled()).isFalse();
        assertThat(response.updatedBy()).isEqualTo("admin-user");
        server.verify();
    }

    @Test
    void updateBatchJobRuntimeControl_unknownJob_preservesNotFoundBoundary() {
        RestClient.Builder restClientBuilder = RestClient.builder().baseUrl("http://stock-batch-test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        StockBatchAdminClient client = new StockBatchAdminClient(restClientBuilder.build(), "secret-token");

        server.expect(requestTo("http://stock-batch-test/internal/stock-batch/v1/jobs/runtime-controls/not-a-job"))
                .andExpect(method(org.springframework.http.HttpMethod.PATCH))
                .andExpect(header("X-Internal-Token", "secret-token"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"success":false,"code":4040000,"message":"Unknown batch job"}
                                """));

        assertThatThrownBy(() -> client.updateBatchJobRuntimeControl(
                "not-a-job",
                new BatchJobRuntimeControlRequest(false, "admin-user")
        ))
                .isInstanceOf(StockException.class)
                .extracting("errorCode")
                .isEqualTo(Code.NOT_FOUND);

        server.verify();
    }

    @Test
    void updateBatchJobRuntimeControl_badRequest_preservesBadRequestBoundary() {
        RestClient.Builder restClientBuilder = RestClient.builder().baseUrl("http://stock-batch-test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        StockBatchAdminClient client = new StockBatchAdminClient(restClientBuilder.build(), "secret-token");

        server.expect(requestTo("http://stock-batch-test/internal/stock-batch/v1/jobs/runtime-controls/%20"))
                .andExpect(method(org.springframework.http.HttpMethod.PATCH))
                .andExpect(header("X-Internal-Token", "secret-token"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"success":false,"code":4000000,"message":"jobName is required"}
                                """));

        assertThatThrownBy(() -> client.updateBatchJobRuntimeControl(
                " ",
                new BatchJobRuntimeControlRequest(false, "admin-user")
        ))
                .isInstanceOf(StockException.class)
                .extracting("errorCode")
                .isEqualTo(Code.BAD_REQUEST);

        server.verify();
    }

    @Test
    void getBatchJobRuntimeControls_internalTokenFailure_isGatewayProblemForStockBack() {
        RestClient.Builder restClientBuilder = RestClient.builder().baseUrl("http://stock-batch-test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        StockBatchAdminClient client = new StockBatchAdminClient(restClientBuilder.build(), "wrong-token");

        server.expect(requestTo("http://stock-batch-test/internal/stock-batch/v1/jobs/runtime-controls"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("X-Internal-Token", "wrong-token"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"success":false,"code":401,"message":"Unauthorized internal batch request"}
                                """));

        assertThatThrownBy(client::getBatchJobRuntimeControls)
                .isInstanceOf(StockException.class)
                .extracting("errorCode")
                .isEqualTo(Code.BAD_GATEWAY);

        server.verify();
    }

    @Test
    void getBatchJobRuntimeControls_failedSuccessWrapper_isGatewayProblemEvenWhenHttpStatusIsOk() {
        RestClient.Builder restClientBuilder = RestClient.builder().baseUrl("http://stock-batch-test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        StockBatchAdminClient client = new StockBatchAdminClient(restClientBuilder.build(), "secret-token");

        server.expect(requestTo("http://stock-batch-test/internal/stock-batch/v1/jobs/runtime-controls"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("X-Internal-Token", "secret-token"))
                .andRespond(withSuccess("""
                        {"success":false,"code":5000000,"message":"Batch control failed","data":[{"jobName":"auto-market","schedulerConfigured":true,"runtimeEnabled":true,"effectiveEnabled":true}]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(client::getBatchJobRuntimeControls)
                .isInstanceOf(StockException.class)
                .extracting("errorCode")
                .isEqualTo(Code.BAD_GATEWAY);

        server.verify();
    }
}
