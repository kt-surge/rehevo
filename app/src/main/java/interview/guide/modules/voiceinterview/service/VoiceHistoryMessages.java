package interview.guide.modules.voiceinterview.service;

import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

/** 仅识别Handler添加的外层角色，不从候选人正文推断角色。 */
final class VoiceHistoryMessages {

  private VoiceHistoryMessages() {}

  static List<Message> from(List<String> history, PromptSanitizer sanitizer) {
    if (history == null || history.isEmpty()) {
      return List.of();
    }
    List<Message> messages = new ArrayList<>(history.size());
    for (String original : history) {
      if (original == null || original.isBlank()) {
        continue;
      }
      String text = sanitizer.sanitize(original);
      if (original.startsWith("面试官：")) {
        messages.add(AssistantMessage.builder().content(text).build());
      } else if (original.startsWith("候选人：")) {
        messages.add(UserMessage.builder().text(text).build());
      } else {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "语音历史缺少可信角色标签");
      }
    }
    return List.copyOf(messages);
  }
}
