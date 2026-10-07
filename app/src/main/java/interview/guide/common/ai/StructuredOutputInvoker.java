package interview.guide.common.ai;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * 统一封装结构化输出调用与重试策略。
 */
@Component
public class StructuredOutputInvoker {

    private static final JsonMapper SCHEMA_JSON_MAPPER = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private static final String STRICT_JSON_INSTRUCTION = """
请仅返回可被 JSON 解析器直接解析的 JSON 对象，并严格满足字段结构要求：
1) 不要输出 Markdown 代码块（如 ```json）。
2) 不要输出任何解释文字、前后缀、注释。
3) 所有字符串内引号必须正确转义。
4) Schema 中出现的每个字段都必须返回；没有内容的字符串返回 ""，没有条目的数组返回 []，不得省略字段或返回 null。
5) 数组中的每个对象也必须返回其 Schema 中的全部字段。
    """;

    private static final String METRIC_INVOCATIONS = "app.ai.structured_output.invocations";
    private static final String METRIC_ATTEMPTS = "app.ai.structured_output.attempts";
    private static final String METRIC_LATENCY = "app.ai.structured_output.latency";
    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_FAILURE = "failure";
    private static final int MAX_CONTEXT_TAG_LENGTH = 48;
    private static final Pattern NON_ALNUM_PATTERN = Pattern.compile("[^a-z0-9_]+");
    private static final Pattern MULTI_UNDERSCORE = Pattern.compile("_+");

    private final int maxAttempts;
    private final boolean includeLastErrorInRetryPrompt;
    private final boolean retryUseRepairPrompt;
    private final boolean retryAppendStrictJsonInstruction;
    private final int errorMessageMaxLength;
    private final boolean metricsEnabled;
    private final boolean schemaValidationEnabled;
    private final MeterRegistry meterRegistry;

    public StructuredOutputInvoker(
        StructuredOutputProperties properties,
        @Autowired(required = false) MeterRegistry meterRegistry
    ) {
        this.maxAttempts = Math.max(1, properties.getStructuredMaxAttempts());
        this.includeLastErrorInRetryPrompt = properties.isStructuredIncludeLastError();
        this.retryUseRepairPrompt = properties.isStructuredRetryUseRepairPrompt();
        this.retryAppendStrictJsonInstruction = properties.isStructuredRetryAppendStrictJsonInstruction();
        this.errorMessageMaxLength = Math.max(20, properties.getStructuredErrorMessageMaxLength());
        this.metricsEnabled = properties.isStructuredMetricsEnabled();
        this.schemaValidationEnabled = properties.isStructuredSchemaValidationEnabled();
        this.meterRegistry = meterRegistry;
    }

    public <T> T invoke(
        ChatClient chatClient,
        String systemPromptWithFormat,
        String userPrompt,
        BeanOutputConverter<T> outputConverter,
        ErrorCode errorCode,
        String errorPrefix,
        String logContext,
        Logger log
    ) {
        return invoke(chatClient, systemPromptWithFormat, userPrompt, outputConverter,
            errorCode, errorPrefix, logContext, log, () -> true);
    }

  /** 预算条件由调用方提供，避免底层清除中断后又发起一次结构重试。 */
  public <T> T invoke(
      ChatClient chatClient,
      String systemPromptWithFormat,
      String userPrompt,
      BeanOutputConverter<T> outputConverter,
      ErrorCode errorCode,
      String errorPrefix,
      String logContext,
      Logger log,
      BooleanSupplier withinBudget
  ) {
        long startNanos = System.nanoTime();
        String contextTag = normalizeContextTag(logContext);
        String securedSystemPrompt = systemPromptWithFormat
            + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;
        Exception lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (Thread.currentThread().isInterrupted() || !withinBudget.getAsBoolean()) {
                lastError = new BusinessException(errorCode, "调用已取消或预算已耗尽");
                break;
            }
            String attemptSystemPrompt = attempt == 1
                ? securedSystemPrompt
                : buildRetrySystemPrompt(securedSystemPrompt, lastError);
            try {
                T result = callStructuredOutput(
                    chatClient, attemptSystemPrompt, userPrompt, outputConverter, logContext, log);
                if (Thread.currentThread().isInterrupted() || !withinBudget.getAsBoolean()) {
                    throw new BusinessException(errorCode, "调用已取消或预算已耗尽，丢弃迟到结果");
                }
                recordAttempt(contextTag, STATUS_SUCCESS);
                recordInvocation(contextTag, STATUS_SUCCESS, startNanos);
                return result;
            } catch (Exception e) {
                lastError = e;
                recordAttempt(contextTag, STATUS_FAILURE);
                if (Thread.currentThread().isInterrupted() || !withinBudget.getAsBoolean()) {
                    break;
                }
                if (attempt < maxAttempts) {
                    log.warn("{}结构化解析失败，准备重试: attempt={}/{}, error={}",
                        logContext, attempt, maxAttempts, e.getMessage());
                } else {
                    log.error("{}结构化解析失败，已达最大重试次数: attempts={}, error={}",
                        logContext, maxAttempts, e.getMessage());
                }
            }
        }

        recordInvocation(contextTag, STATUS_FAILURE, startNanos);
        throw new BusinessException(
            errorCode,
            errorPrefix + (lastError != null ? lastError.getMessage() : "unknown")
        );
    }

    private <T> T callStructuredOutput(
        ChatClient chatClient,
        String systemPrompt,
        String userPrompt,
        BeanOutputConverter<T> outputConverter,
        String logContext,
        Logger log
    ) {
        String content = chatClient.prompt()
            .system(systemPrompt)
            .user(userPrompt)
            .call().content();
        if (schemaValidationEnabled) {
            // SDK validateSchema() 自带递归重试，会与本类预算叠加且耗尽后仍返回最后结果。
            // 本地执行同一 DRAFT_2020_12 校验，解析与 Schema 失败统一交给中央重试。
            validateOutputSchema(content, outputConverter);
        }
        return convertWithRepair(content, outputConverter, logContext, log);
    }

    private <T> void validateOutputSchema(String content, BeanOutputConverter<T> converter) {
        var schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(SCHEMA_JSON_MAPPER.readTree(converter.getJsonSchema()));
        var errors = schema.validate(SCHEMA_JSON_MAPPER.readTree(content));
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR,
                "结构化输出不满足 Schema，存在 " + errors.size() + " 处字段错误");
        }
    }

    private <T> T convertWithRepair(
        String content,
        BeanOutputConverter<T> outputConverter,
        String logContext,
        Logger log
    ) {
        try {
            return outputConverter.convert(content);
        } catch (Exception firstError) {
            String repaired = repairUnescapedQuotesInJsonStrings(content);
            if (!repaired.equals(content)) {
                try {
                    T result = outputConverter.convert(repaired);
                    log.warn("{}结构化 JSON 存在未转义引号，已在本地修复后解析成功", logContext);
                    return result;
                } catch (Exception repairError) {
                    firstError.addSuppressed(repairError);
                }
            }
            throw firstError;
        }
    }

    private String repairUnescapedQuotesInJsonStrings(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }
        StringBuilder repaired = new StringBuilder(content.length() + 16);
        boolean inString = false;
        boolean escaping = false;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (!inString) {
                if (ch == '"') {
                    inString = true;
                }
                repaired.append(ch);
                continue;
            }

            if (escaping) {
                repaired.append(ch);
                escaping = false;
                continue;
            }
            if (ch == '\\') {
                repaired.append(ch);
                escaping = true;
                continue;
            }
            if (ch == '"') {
                if (isLikelyJsonStringTerminator(content, i + 1)) {
                    inString = false;
                    repaired.append(ch);
                } else {
                    repaired.append("\\\"");
                }
                continue;
            }
            repaired.append(ch);
        }
        return repaired.toString();
    }

    private boolean isLikelyJsonStringTerminator(String content, int start) {
        for (int i = start; i < content.length(); i++) {
            char next = content.charAt(i);
            if (Character.isWhitespace(next)) {
                continue;
            }
            return next == ',' || next == '}' || next == ']' || next == ':';
        }
        return true;
    }

    private String buildRetrySystemPrompt(String systemPromptWithFormat, Exception lastError) {
        if (!retryUseRepairPrompt) {
            return systemPromptWithFormat;
        }

        StringBuilder prompt = new StringBuilder(systemPromptWithFormat)
            .append("\n\n");

        if (retryAppendStrictJsonInstruction) {
            prompt.append(STRICT_JSON_INSTRUCTION).append('\n');
        }
        prompt.append("上次输出解析失败，请仅返回合法 JSON。");

        if (includeLastErrorInRetryPrompt && lastError != null && lastError.getMessage() != null) {
            prompt.append("\n上次失败原因：")
                .append(sanitizeErrorMessage(lastError.getMessage()));
        }
        return prompt.toString();
    }

    private String sanitizeErrorMessage(String message) {
        String oneLine = message.replace('\n', ' ').replace('\r', ' ').trim();
        if (oneLine.length() > errorMessageMaxLength) {
            return oneLine.substring(0, errorMessageMaxLength) + "...";
        }
        return oneLine;
    }

    private void recordAttempt(String contextTag, String status) {
        if (!isMetricsAvailable()) {
            return;
        }
        meterRegistry.counter(
            METRIC_ATTEMPTS,
            Tags.of("context", contextTag, "status", status)
        ).increment();
    }

    private void recordInvocation(String contextTag, String status, long startNanos) {
        if (!isMetricsAvailable()) {
            return;
        }
        Tags tags = Tags.of("context", contextTag, "status", status);
        meterRegistry.counter(METRIC_INVOCATIONS, tags).increment();
        meterRegistry.timer(METRIC_LATENCY, tags)
            .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }

    private boolean isMetricsAvailable() {
        return metricsEnabled && meterRegistry != null;
    }

    private String normalizeContextTag(String raw) {
        String source = (raw == null || raw.isBlank()) ? "unknown" : raw;
        String normalized = source.toLowerCase(Locale.ROOT).trim().replace(' ', '_');
        normalized = NON_ALNUM_PATTERN.matcher(normalized).replaceAll("_");
        normalized = MULTI_UNDERSCORE.matcher(normalized).replaceAll("_");
        normalized = normalized.replaceAll("^_+|_+$", "");
        if (normalized.isBlank()) {
            normalized = "unknown";
        }
        if (normalized.length() > MAX_CONTEXT_TAG_LENGTH) {
            normalized = normalized.substring(0, MAX_CONTEXT_TAG_LENGTH);
        }
        return normalized;
    }
}
