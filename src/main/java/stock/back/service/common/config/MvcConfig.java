package stock.back.service.common.config;

import auth.common.core.context.RequirePrincipalRoleFilter;
import auth.common.core.context.UserContextArgumentResolver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new UserContextArgumentResolver());
    }

    @Bean
    public FilterRegistrationBean<RequirePrincipalRoleFilter> requirePrincipalRoleFilter(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping
    ) {
        FilterRegistrationBean<RequirePrincipalRoleFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RequirePrincipalRoleFilter(handlerMapping));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 50);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
