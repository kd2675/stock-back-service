package stock.back.service.common.config;

import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

class FeignHeaderRelayConfigTest {

    private final FeignHeaderRelayConfig config = new FeignHeaderRelayConfig();

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void userContextHeaderRelayInterceptor_currentRequest_relaysUserHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer access-token");
        request.addHeader("X-User-Key", "user-1");
        request.addHeader("X-User-Name", "harry");
        request.addHeader("X-User-Role", "USER");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        RequestTemplate template = new RequestTemplate();

        config.userContextHeaderRelayInterceptor().apply(template);

        assertThat(template.headers()).containsEntry("Authorization", java.util.List.of("Bearer access-token"));
        assertThat(template.headers()).containsEntry("X-User-Key", java.util.List.of("user-1"));
        assertThat(template.headers()).containsEntry("X-User-Name", java.util.List.of("harry"));
        assertThat(template.headers()).containsEntry("X-User-Role", java.util.List.of("USER"));
    }

    @Test
    void userContextHeaderRelayInterceptor_noCurrentRequest_doesNotAddHeaders() {
        RequestTemplate template = new RequestTemplate();

        config.userContextHeaderRelayInterceptor().apply(template);

        assertThat(template.headers()).isEmpty();
    }
}
