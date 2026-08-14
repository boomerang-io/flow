package io.boomerang.workflow.config;

import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractJacksonHttpMessageConverter;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;

final class YamlJacksonHttpMessageConverter extends AbstractJacksonHttpMessageConverter<YAMLMapper> {
  YamlJacksonHttpMessageConverter() {
    super(
        YAMLMapper.builder()
            .enable(YAMLWriteFeature.LITERAL_BLOCK_STYLE)
            .enable(YAMLWriteFeature.MINIMIZE_QUOTES)
            .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER),
        MediaType.parseMediaType("application/x-yaml"));
  }
}
