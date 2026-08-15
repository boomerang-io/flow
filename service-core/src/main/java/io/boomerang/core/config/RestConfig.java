package io.boomerang.core.config;

import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.net.ssl.SSLContext;
import org.apache.hc.client5.http.ConnectionKeepAliveStrategy;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.apache.hc.core5.ssl.TrustStrategy;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;

/*
 * Every template carries real transport timeouts: connect 10s, idle read 60s, pool-lease 10s.
 * Log streaming uses the dedicated streamingRestTemplate (long idle read), never a control template.
 */
@Component
public class RestConfig {

  @Value("${proxy.host:#{null}}")
  private Optional<String> boomerangProxyHost;

  @Value("${proxy.port:#{null}}")
  private Optional<String> boomerangProxyPort;

  private static final int MAX_ROUTE_CONNECTIONS = 200;
  private static final int MAX_TOTAL_CONNECTIONS = 200;
  private static final int CONNECT_TIMEOUT_SECONDS = 10;
  private static final int READ_TIMEOUT_SECONDS = 60;
  private static final int POOL_LEASE_TIMEOUT_SECONDS = 10;
  private static final int STREAMING_READ_MINUTES = 10;
  private static final int KEEP_ALIVE_MINUTES = 5;

  @Bean
  @Qualifier("externalRestTemplate")
  public RestTemplate externalRestTemplate() {
    if (this.boomerangProxyHost.isPresent()
        && !this.boomerangProxyHost.get().isBlank()
        && this.boomerangProxyPort.isPresent()
        && !this.boomerangProxyPort.get().isBlank()) {
      HttpComponentsClientHttpRequestFactory clientHttpRequestFactory =
          new HttpComponentsClientHttpRequestFactory(
              HttpClientBuilder.create()
                  .setProxy(
                      new HttpHost(
                          "http",
                          this.boomerangProxyHost.get(),
                          Integer.valueOf(this.boomerangProxyPort.get())))
                  .setDefaultRequestConfig(controlRequestConfig())
                  .setConnectionManager(poolingConnectionManager())
                  .setKeepAliveStrategy(connectionKeepAliveStrategy())
                  .build());
      return new RestTemplate(clientHttpRequestFactory);
    }
    return internalRestTemplate();
  }

  @Bean
  @Qualifier("insecureRestTemplate")
  public RestTemplate insecureRestTemplate()
      throws KeyManagementException, NoSuchAlgorithmException, KeyStoreException {
    final RestTemplate restTemplate =
        new RestTemplate(insecureRequestFactory(Timeout.ofSeconds(READ_TIMEOUT_SECONDS)));
    setRestTemplateInterceptors(restTemplate);
    return restTemplate;
  }

  @Bean
  @Qualifier("internalRestTemplate")
  public RestTemplate internalRestTemplate() {
    return new RestTemplateBuilder().requestFactory(this::clientHttpRequestFactory).build();
  }

  @Bean
  @Qualifier("selfRestTemplate")
  public RestTemplate selfRestTemplate() {
    final RestTemplate template = new RestTemplate(clientHttpRequestFactory());
    setRestTemplateInterceptors(template);
    return template;
  }

  /*
   * Log/byte streaming: long idle read so quiet-but-healthy streams are not cut at the 60s
   * control read timeout; trust-all SSL as agents may present self-signed certs.
   */
  @Bean
  @Qualifier("streamingRestTemplate")
  public RestTemplate streamingRestTemplate()
      throws KeyManagementException, NoSuchAlgorithmException, KeyStoreException {
    final RestTemplate restTemplate =
        new RestTemplate(insecureRequestFactory(Timeout.ofMinutes(STREAMING_READ_MINUTES)));
    setRestTemplateInterceptors(restTemplate);
    return restTemplate;
  }

  private HttpComponentsClientHttpRequestFactory insecureRequestFactory(Timeout socketTimeout)
      throws KeyManagementException, NoSuchAlgorithmException, KeyStoreException {
    final TrustStrategy acceptingTrustStrategy = (X509Certificate[] chain, String authType) -> true;
    final SSLContext sslContext =
        SSLContextBuilder.create().loadTrustMaterial(null, acceptingTrustStrategy).build();
    final DefaultClientTlsStrategy tlsStrategy = new DefaultClientTlsStrategy(sslContext);
    final CloseableHttpClient httpClient =
        HttpClients.custom()
            .setDefaultRequestConfig(controlRequestConfig())
            .setConnectionManager(
                PoolingHttpClientConnectionManagerBuilder.create()
                    .setTlsSocketStrategy(tlsStrategy)
                    .setDefaultConnectionConfig(
                        ConnectionConfig.custom()
                            .setSocketTimeout(socketTimeout)
                            .setConnectTimeout(Timeout.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                            .build())
                    .build())
            .setKeepAliveStrategy(connectionKeepAliveStrategy())
            .build();
    final HttpComponentsClientHttpRequestFactory requestFactory =
        new HttpComponentsClientHttpRequestFactory();
    requestFactory.setHttpClient(httpClient);
    return requestFactory;
  }

  private void setRestTemplateInterceptors(RestTemplate restTemplate) {
    List<ClientHttpRequestInterceptor> interceptors = restTemplate.getInterceptors();
    if (CollectionUtils.isEmpty(interceptors)) {
      interceptors = new ArrayList<>();
    }
    restTemplate.setInterceptors(interceptors);
  }

  public HttpComponentsClientHttpRequestFactory clientHttpRequestFactory() {
    HttpComponentsClientHttpRequestFactory clientHttpRequestFactory =
        new HttpComponentsClientHttpRequestFactory();
    clientHttpRequestFactory.setHttpClient(httpClient());
    return clientHttpRequestFactory;
  }

  public CloseableHttpClient httpClient() {
    return HttpClients.custom()
        .setDefaultRequestConfig(controlRequestConfig())
        .setConnectionManager(poolingConnectionManager())
        .setKeepAliveStrategy(connectionKeepAliveStrategy())
        .build();
  }

  private RequestConfig controlRequestConfig() {
    // Pool-lease: fail fast when the pool is exhausted instead of stacking blocked threads.
    return RequestConfig.custom()
        .setConnectionRequestTimeout(Timeout.ofSeconds(POOL_LEASE_TIMEOUT_SECONDS))
        .build();
  }

  public PoolingHttpClientConnectionManager poolingConnectionManager() {
    PoolingHttpClientConnectionManager poolingConnectionManager =
        new PoolingHttpClientConnectionManager();
    poolingConnectionManager.setMaxTotal(MAX_TOTAL_CONNECTIONS);
    poolingConnectionManager.setDefaultMaxPerRoute(MAX_ROUTE_CONNECTIONS);
    poolingConnectionManager.setDefaultConnectionConfig(
        ConnectionConfig.custom()
            .setSocketTimeout(Timeout.ofSeconds(READ_TIMEOUT_SECONDS))
            .setConnectTimeout(Timeout.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .build());
    return poolingConnectionManager;
  }

  public ConnectionKeepAliveStrategy connectionKeepAliveStrategy() {
    return (httpResponse, httpContext) -> TimeValue.ofMinutes(KEEP_ALIVE_MINUTES);
  }
}
