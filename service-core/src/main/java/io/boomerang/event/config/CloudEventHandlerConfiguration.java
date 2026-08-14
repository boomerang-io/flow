package io.boomerang.config;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import io.cloudevents.spring.mvc.CloudEventHttpMessageConverter;

@Configuration
public class CloudEventHandlerConfiguration implements WebMvcConfigurer {

    @Override
    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.add(0, new CloudEventHttpMessageConverter());
    }

}