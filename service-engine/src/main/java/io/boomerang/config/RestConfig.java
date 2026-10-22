package io.boomerang.config;

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
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.apache.hc.core5.ssl.TrustStrategy;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;

@Component
public class RestConfig {

  @Value("${proxy.host:#{null}}")
  private Optional<String> boomerangProxyHost;

  @Value("${proxy.port:#{null}}")
  private Optional<String> boomerangProxyPort;

  private static final int MAX_ROUTE_CONNECTIONS = 200;
  private static final int MAX_TOTAL_CONNECTIONS = 200;
  private static final int DEFAULT_KEEP_ALIVE_TIME = Integer.MAX_VALUE;
  private static final int CONNECTION_TIMEOUT = Integer.MAX_VALUE;
  private static final int REQUEST_TIMEOUT = Integer.MAX_VALUE;
  private static final int SOCKET_TIMEOUT = Integer.MAX_VALUE;

  @Bean
  @Qualifier("externalRestTemplate")
  public RestTemplate externalRestTemplate() {
    if (this.boomerangProxyHost.isPresent() && !this.boomerangProxyHost.get().isBlank()
        && this.boomerangProxyPort.isPresent() && !this.boomerangProxyPort.get().isBlank()) {
      HttpComponentsClientHttpRequestFactory clientHttpRequestFactory =
          new HttpComponentsClientHttpRequestFactory(
              HttpClientBuilder.create().setProxy(new HttpHost( "http",this.boomerangProxyHost.get(),
                  Integer.valueOf(this.boomerangProxyPort.get()))).build());
      return new RestTemplate(clientHttpRequestFactory);
    }
    return internalRestTemplate();
  }

  @Bean
  @Qualifier("insecureRestTemplate")
  public RestTemplate insecureRestTemplate()
      throws KeyManagementException, NoSuchAlgorithmException, KeyStoreException {

    final TrustStrategy acceptingTrustStrategy = (X509Certificate[] chain, String authType) -> true;
    final SSLContext sslContext = SSLContextBuilder.create()
        .loadTrustMaterial(null,acceptingTrustStrategy).build();

    final SSLConnectionSocketFactory csf = new SSLConnectionSocketFactory(sslContext);
    final CloseableHttpClient httpClient = HttpClients.custom()
        .setConnectionManager(
            PoolingHttpClientConnectionManagerBuilder.create()
                .setSSLSocketFactory(csf).build()).build();
    final HttpComponentsClientHttpRequestFactory requestFactory =
        new HttpComponentsClientHttpRequestFactory();
    requestFactory.setHttpClient(httpClient);
    final RestTemplate restTemplate = new RestTemplate(requestFactory);
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
    final HttpComponentsClientHttpRequestFactory requestFactory =
        new HttpComponentsClientHttpRequestFactory();
    final RestTemplate template = new RestTemplate(requestFactory);
    setRestTemplateInterceptors(template);
    return template;
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

    RequestConfig requestConfig = RequestConfig.custom().
        setConnectionRequestTimeout(Timeout.ofMinutes(CONNECTION_TIMEOUT)).build();
    return HttpClients.custom().setDefaultRequestConfig(requestConfig)
        .setConnectionManager(poolingConnectionManager())
        .setKeepAliveStrategy(connectionKeepAliveStrategy()).build();
  }

  public PoolingHttpClientConnectionManager poolingConnectionManager() {
    PoolingHttpClientConnectionManager poolingConnectionManager =
        new PoolingHttpClientConnectionManager();
    poolingConnectionManager.setMaxTotal(MAX_TOTAL_CONNECTIONS);
    poolingConnectionManager.setDefaultMaxPerRoute(MAX_ROUTE_CONNECTIONS);
    poolingConnectionManager.setDefaultConnectionConfig(ConnectionConfig.custom()
        .setSocketTimeout(Timeout.ofMinutes(1))
        .setConnectTimeout(Timeout.ofMinutes(1))
        .build());
    return poolingConnectionManager;
  }

  public ConnectionKeepAliveStrategy connectionKeepAliveStrategy() {
    return (httpResponse, httpContext) -> {
      return TimeValue.MAX_VALUE ;
    };
  }
}
