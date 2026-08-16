package io.boomerang.event.config;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import io.cloudevents.spring.mvc.CloudEventHttpMessageConverter;

@Configuration
public class CloudEventHandlerConfiguration implements WebMvcConfigurer {

    // NOTE: this MUST be extendMessageConverters, never configureMessageConverters.
    // WebMvcConfigurationSupport#getMessageConverters() only calls
    // addDefaultHttpMessageConverters() (which registers the JSON/String/byte[]/Resource/etc.
    // converters) when configureMessageConverters() left the list EMPTY. Adding this converter
    // via configureMessageConverters() therefore suppressed every default converter app-wide -
    // including JSON - leaving YamlJacksonHttpMessageConverter (registered via
    // extendMessageConverters in YamlConfiguration) as the only converter left able to write a
    // generic POJO, so it silently won every response once no default was left to compete with.
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.add(0, new CloudEventHttpMessageConverter());
    }

}