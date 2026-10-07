package interview.guide.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("聊天模型的集中请求参数绑定")
class LlmChatModelOptionsBindingTest {
  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(LlmProviderProperties.class)
  static class Config {}

  @Test
  @DisplayName("实际 application.yml 保留带点型号和下划线请求字段且不扩展到其他型号")
  void applicationYamlKeepsExactModelAndWireKeys() {
    new ApplicationContextRunner().withUserConfiguration(Config.class)
        .withInitializer(context -> {
          try {
            for (var source : new YamlPropertySourceLoader().load("app-model-options",
                new ClassPathResource("application.yml"))) {
              context.getEnvironment().getPropertySources().addLast(source);
            }
          } catch (IOException error) {
            throw new UncheckedIOException(error);
          }
        }).run(context -> {
          assertThat(context).hasNotFailed();
          var properties = context.getBean(LlmProviderProperties.class);
          assertThat(properties.chatExtraBodyForModel("qwen3.8-flash"))
              .containsEntry("enable_thinking", false)
              .containsEntry("preserve_thinking", false);
          assertThat(properties.chatExtraBodyForModel("qwen3.7-flash")).isEmpty();
        });
  }
}
