package io.boomerang.workflow.config;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the YAML message converter used by the {@code produces = "application/x-yaml"}
 * Task-as-Tekton-YAML endpoints ({@code TaskControllerV2}/{@code WorkspaceTaskControllerV2}).
 *
 * <p>Those endpoints are mapped on the SAME path as a plain-JSON sibling (e.g. {@code GET
 * /{name}} with no {@code produces} vs. {@code GET /{name}} with {@code produces =
 * "application/x-yaml"}). With no {@code Accept} header, Spring's default {@code
 * HeaderContentNegotiationStrategy} resolves the request to the wildcard media type, both
 * mappings match, and {@code ProducesRequestCondition} ranks the handler with the
 * narrower/explicit {@code produces} as the better match - so the YAML endpoint would win over
 * the JSON one. Declaring {@code application/json} as the negotiation default content type closes
 * that gap: the wildcard (the sentinel for "the header strategy found nothing to say") is skipped
 * in favour of the default, so an absent Accept header resolves to JSON and only an explicit
 * {@code Accept: application/x-yaml} routes to the YAML-producing handler.
 */
@Configuration
public class YamlConfiguration implements WebMvcConfigurer {
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.add(new YamlJacksonHttpMessageConverter());
    }

    @Override
    public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
        configurer.defaultContentType(MediaType.APPLICATION_JSON);
    }
}
