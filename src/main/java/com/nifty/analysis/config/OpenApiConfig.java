package com.nifty.analysis.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI niftyOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Nifty Analysis Engine API")
                        .description("REST API documentation for the Nifty Options Analysis and Signal Engine")
                        .version("1.0.0"));
    }
}
