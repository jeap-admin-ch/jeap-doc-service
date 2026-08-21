package ch.admin.bit.jeap.doc.web.api.upload;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the upload endpoint: its limits and the check that runs before the parameters are bound.
 */
@Configuration
@EnableConfigurationProperties(UploadProperties.class)
class UploadConfiguration implements WebMvcConfigurer {

    @Bean
    UploadParameterInterceptor uploadParameterInterceptor() {
        return new UploadParameterInterceptor();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(uploadParameterInterceptor()).addPathPatterns(UploadController.UPLOADS_PATH + "/**");
    }
}
