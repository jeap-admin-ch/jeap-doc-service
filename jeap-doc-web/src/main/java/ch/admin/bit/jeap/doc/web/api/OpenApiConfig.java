package ch.admin.bit.jeap.doc.web.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI docServiceOpenApi() {
        return new OpenAPI().info(new Info()
                .title("jEAP Doc Service API")
                .description("Upload and retrieval of documentation")
                .version("v1"));
    }
}
