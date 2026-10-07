package interview.guide.common.ai;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.Timeout;
import com.openai.credential.BearerTokenCredential;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

import java.time.Duration;
import java.util.regex.Pattern;

public final class ApiPathResolver {

  static final int DEFAULT_CONNECT_TIMEOUT = 10000;
  static final int DEFAULT_READ_TIMEOUT = 60000;

  private static final Pattern TRAILING_VERSION = Pattern.compile("/v\\d+[a-zA-Z0-9]*$");

  private ApiPathResolver() {}

  public static OpenAIClient buildOpenAiClient(String baseUrl, String apiKey) {
    return buildOpenAiClient(baseUrl, apiKey, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
  }

  public static OpenAIClient buildOpenAiClient(String baseUrl, String apiKey,
      int connectTimeout, int readTimeout) {
    Timeout timeout = Timeout.builder()
        .connect(Duration.ofMillis(timeoutOrDefault(connectTimeout, DEFAULT_CONNECT_TIMEOUT)))
        .read(Duration.ofMillis(timeoutOrDefault(readTimeout, DEFAULT_READ_TIMEOUT)))
        .build();
    ClientOptions options = ClientOptions.Companion.builder()
        .apiKey(apiKey)
        .credential(BearerTokenCredential.create(apiKey))
        .baseUrl(resolveVersionedBaseUrl(baseUrl))
        .timeout(timeout)
        .httpClient(SpringAiOpenAiHttpClient.builder().timeout(timeout).interceptor(chain -> {
          // 同步评估线程持有任务范围；Call.cancel() 能打断响应头和正文的阻塞读取。
          AiCallCancellation.registerCurrent(chain.call()::cancel);
          return chain.proceed(chain.request());
        }).build())
        .build();
    return new OpenAIClientImpl(options);
  }

  static int timeoutOrDefault(int configuredTimeout, int defaultTimeout) {
    return configuredTimeout > 0 ? configuredTimeout : defaultTimeout;
  }

  public static String resolveVersionedBaseUrl(String baseUrl) {
    String stripped = stripTrailingSlashes(baseUrl);
    if (baseUrlContainsVersion(stripped)) {
      return stripped;
    }
    return stripped + "/v1";
  }

  public static boolean baseUrlContainsVersion(String baseUrl) {
    if (baseUrl == null || baseUrl.isBlank()) {
      return false;
    }
    String stripped = stripTrailingSlashes(baseUrl.trim());
    return TRAILING_VERSION.matcher(stripped).find();
  }

  public static String stripTrailingSlashes(String value) {
    if (value == null) {
      return "";
    }
    String result = value.trim();
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }
}
