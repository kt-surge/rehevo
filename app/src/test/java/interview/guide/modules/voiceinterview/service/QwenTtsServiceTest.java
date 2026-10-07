package interview.guide.modules.voiceinterview.service;

import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.common.exception.BusinessException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("QwenTtsService Unit Tests")
class QwenTtsServiceTest {

    private QwenTtsService ttsService;

    @BeforeEach
    void setUp() {
        VoiceInterviewProperties properties = new VoiceInterviewProperties();
        VoiceInterviewProperties.QwenTtsConfig tts = properties.getQwen().getTts();
        tts.setModel("qwen-audio-3.0-tts-flash");
        tts.setApiKey("test-api-key");
        tts.setVoice("longanhuan_v3.6");
        tts.setFormat("pcm");
        tts.setSampleRate(16000);
        tts.setMode("server_commit");
        tts.setLanguageType("Chinese");
        tts.setSpeechRate(1.0f);
        tts.setVolume(60);

        ttsService = new QwenTtsService(properties);
    }

    @Test
    @DisplayName("Should initialize service successfully")
    void testInit() {
        assertDoesNotThrow(() -> ttsService.init());
    }

  @Test
  @DisplayName("帧模式拒绝非 24000 采样率，避免音频协议误标")
  void rejectsIncompatibleFrameSampleRate() {
    assertThrows(BusinessException.class,
        () -> ttsService.prepareFrames("测试文本", Duration.ofSeconds(1), pcm -> { }));
  }

    @Test
    @DisplayName("Should return empty array for empty text")
    void testSynthesizeEmptyText() {
        ttsService.init();

        byte[] result = ttsService.synthesize("");

        assertNotNull(result);
        assertEquals(0, result.length);
    }

    @Test
    @DisplayName("Should return empty array for null text")
    void testSynthesizeNullText() {
        ttsService.init();

        byte[] result = ttsService.synthesize(null);

        assertNotNull(result);
        assertEquals(0, result.length);
    }

    @Test
    @DisplayName("Should return empty array for whitespace text")
    void testSynthesizeWhitespaceText() {
        ttsService.init();

        byte[] result = ttsService.synthesize("   ");

        assertNotNull(result);
        assertEquals(0, result.length);
    }

    @Test
    @DisplayName("TTS 返回空缓冲区时安全降级为空音频")
    void copyAudioReturnsEmptyForNullOrEmptyBuffer() {
        assertArrayEquals(new byte[0], QwenTtsService.copyAudio(null));
        assertArrayEquals(new byte[0], QwenTtsService.copyAudio(java.nio.ByteBuffer.allocate(0)));
    }

    @Test
    @DisplayName("复制音频时不改变调用方缓冲区的读取位置")
    void copyAudioPreservesCallerBufferPosition() {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(new byte[] {1, 2, 3});
        buffer.position(1);

        byte[] copied = QwenTtsService.copyAudio(buffer);

        assertArrayEquals(new byte[] {2, 3}, copied);
        assertEquals(1, buffer.position());
    }

    @Test
    @DisplayName("Should cleanup resources on destroy")
    void testDestroy() {
        ttsService.init();

        // Destroy should cleanup resources without error
        assertDoesNotThrow(() -> ttsService.destroy());
    }

    @Test
    @DisplayName("reload 应更新所有 TTS 配置字段")
    void testReloadUpdatesAllFields() throws Exception {
        VoiceInterviewProperties newProps = new VoiceInterviewProperties();
        VoiceInterviewProperties.QwenTtsConfig newTts = newProps.getQwen().getTts();
        newTts.setModel("new-tts-model");
        newTts.setApiKey("new-api-key");
        newTts.setVoice("Serena");
        newTts.setFormat("mp3");
        newTts.setSampleRate(48000);
        newTts.setMode("user_commit");
        newTts.setLanguageType("English");
        newTts.setSpeechRate(1.5f);
        newTts.setVolume(80);

        ttsService.reload(newProps);

        assertEquals("new-tts-model", field(ttsService, "model"));
        assertEquals("new-api-key", field(ttsService, "apiKey"));
        assertEquals("Serena", field(ttsService, "voice"));
        assertEquals("mp3", field(ttsService, "format"));
        assertEquals(48000, field(ttsService, "sampleRate"));
        assertEquals("user_commit", field(ttsService, "mode"));
        assertEquals("English", field(ttsService, "languageType"));
        assertEquals(1.5f, field(ttsService, "speechRate"));
        assertEquals(80, field(ttsService, "volume"));
    }

    private static Object field(Object obj, String name) throws Exception {
        java.lang.reflect.Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(obj);
    }
}
