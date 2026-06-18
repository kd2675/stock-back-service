package stock.back.service.common.config;

import feign.RequestInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Configuration
public class FeignHeaderRelayConfig {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String USER_KEY_HEADER = "X-User-Key";
    private static final String USER_NAME_HEADER = "X-User-Name";
    private static final String USER_ROLE_HEADER = "X-User-Role";

    @Bean
    public RequestInterceptor userContextHeaderRelayInterceptor() {
        return template -> {
            ServletRequestAttributes attributes = currentRequestAttributes();
            if (attributes == null) {
                return;
            }
            HttpServletRequest request = attributes.getRequest();
            relayHeader(template, request, AUTHORIZATION_HEADER);
            relayHeader(template, request, USER_KEY_HEADER);
            relayHeader(template, request, USER_NAME_HEADER);
            relayHeader(template, request, USER_ROLE_HEADER);
        };
    }

    private ServletRequestAttributes currentRequestAttributes() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes;
        }
        return null;
    }

    private void relayHeader(feign.RequestTemplate template, HttpServletRequest request, String headerName) {
        String value = request.getHeader(headerName);
        if (value != null && !value.isBlank()) {
            template.header(headerName, value);
        }
    }
}
