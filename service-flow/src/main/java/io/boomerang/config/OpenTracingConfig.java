package io.boomerang.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import io.jaegertracing.Configuration;
import io.jaegertracing.Configuration.SenderConfiguration;
import io.jaegertracing.internal.JaegerTracer;
import io.jaegertracing.internal.reporters.InMemoryReporter;
import io.jaegertracing.internal.samplers.ConstSampler;
import io.jaegertracing.spi.Reporter;
import io.jaegertracing.spi.Sampler;
import io.opentracing.Tracer;

@org.springframework.context.annotation.Configuration
public class OpenTracingConfig {

  @Value("${opentracing.jaeger.service-name:boomerang}")
  private String serviceName;

  @Value("${opentracing.jaeger.udp-sender.host:localhost}")
  private String agentHost;

  @Value("${opentracing.jaeger.udp-sender.port:6831}")
  private Integer agentPort;

  @Value("${opentracing.jaeger.http-sender.url:http://localhost:34268/api/traces}")
  private String httpSenderUrl;

  @Value("${opentracing.jaeger.remote-controlled-sampler.host-port:localhost:5778}")
  private String samplerManagerHostPort;

  @Value("${opentracing.jaeger.sampler-type:probabilistic}")
  private String valueSamplerType;

  @Value("${opentracing.jaeger.sampler-param:1}")
  private String samplerParam;

  @Bean
  @ConditionalOnProperty(value = "opentracing.jaeger.enabled", havingValue = "true",
      matchIfMissing = false)
  public Tracer jaegerTracer() {

    Configuration.SamplerConfiguration samplerConfig = new Configuration.SamplerConfiguration();

    SamplerType samplerType = SamplerType.fromValue(valueSamplerType);

    Number samplerParamNumber = getSamplerParamNumber(samplerType, samplerParam);

    samplerConfig.withType(samplerType.getValue()).withParam(samplerParamNumber)
        .withManagerHostPort(samplerManagerHostPort);

    SenderConfiguration senderConfiguration = new SenderConfiguration().withAgentHost(agentHost)
        .withAgentPort(agentPort).withEndpoint(httpSenderUrl);

    Configuration.ReporterConfiguration reporterConfig = Configuration.ReporterConfiguration
        .fromEnv().withLogSpans(true).withSender(senderConfiguration);

    Configuration config =
        new Configuration(serviceName).withSampler(samplerConfig).withReporter(reporterConfig);

    return config.getTracer();
  }

  @Bean
  @ConditionalOnProperty(value = "opentracing.jaeger.enabled", havingValue = "false",
      matchIfMissing = true)
  public Tracer disabledTracer() {
    final Reporter reporter = new InMemoryReporter();
    final Sampler sampler = new ConstSampler(false);
    return new JaegerTracer.Builder(serviceName).withReporter(reporter).withSampler(sampler)
        .build();
  }

  private static Number getSamplerParamNumber(SamplerType samplerType, String value) {
    Number retValue = null;
    switch (samplerType) {
      case CONST:
      case RATE_LIMIT:
        retValue = Integer.valueOf(value);
        break;
      case PROBABILISTIC:
      case REMOTE_CONTROLLED:
        retValue = Double.valueOf(value);
        break;
      default:
        throw new NumberFormatException();
    }

    return retValue;
  }

}
