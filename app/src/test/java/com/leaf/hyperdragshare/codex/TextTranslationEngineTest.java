package com.leaf.hyperdragshare.codex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class TextTranslationEngineTest {
    @Test
    public void cjkTextDetectsEnglishAsReverseTarget() {
        assertEquals("en", TextTranslationEngine.resolveTargetLanguage("今天天气不错", 0));
    }

    @Test
    public void latinTextDetectsChineseAsReverseTarget() {
        assertEquals("zh", TextTranslationEngine.resolveTargetLanguage("hello world", 0));
    }

    @Test
    public void explicitChineseTargetWins() {
        assertEquals("zh", TextTranslationEngine.resolveTargetLanguage("hello", 1));
    }

    @Test
    public void explicitEnglishTargetWins() {
        assertEquals("en", TextTranslationEngine.resolveTargetLanguage("今天天气不错", 2));
    }

    @Test
    public void containsCjkDetectsUnifiedIdeographs() {
        assertTrue(TextTranslationEngine.containsCjk("abc中def"));
    }

    @Test
    public void containsCjkDetectsExtensionBlock() {
        assertTrue(TextTranslationEngine.containsCjk("\uD85B\uDF3F"));
    }

    @Test
    public void containsCjkRejectsPureLatin() {
        assertFalse(TextTranslationEngine.containsCjk("only latin 123"));
    }

    @Test
    public void containsCjkRejectsEmpty() {
        assertFalse(TextTranslationEngine.containsCjk(""));
        assertFalse(TextTranslationEngine.containsCjk(null));
    }

    @Test
    public void buildChatUrlAppendsPathToBaseUrl() {
        assertEquals(
                "https://api.example.com/v1/chat/completions",
                TextTranslationEngine.buildChatUrl("https://api.example.com/v1"));
    }

    @Test
    public void buildChatUrlHandlesTrailingSlash() {
        assertEquals(
                "https://api.example.com/v1/chat/completions",
                TextTranslationEngine.buildChatUrl("https://api.example.com/v1/"));
    }

    @Test
    public void buildChatUrlKeepsExistingCompletionsPath() {
        assertEquals(
                "https://api.example.com/v1/chat/completions",
                TextTranslationEngine.buildChatUrl("https://api.example.com/v1/chat/completions"));
    }

    @Test
    public void buildChatUrlFallsBackToDefaultBase() {
        assertTrue(
                TextTranslationEngine.buildChatUrl("")
                        .startsWith(TranslationSettings.DEFAULT_BASE_URL + "/chat/completions"));
    }

    @Test
    public void buildRequestJsonContainsModelMessagesAndTarget() throws Exception {
        String json = TextTranslationEngine.buildRequestJson("my-model", "en", "你好");
        JSONObject body = new JSONObject(json);
        assertEquals("my-model", body.getString("model"));
        assertEquals(2, body.getJSONArray("messages").length());
        assertTrue(body.getJSONArray("messages").getJSONObject(0).getString("content")
                .contains("English"));
        assertEquals(
                "你好",
                body.getJSONArray("messages").getJSONObject(1).getString("content"));
    }

    @Test
    public void buildRequestJsonNamesChineseTarget() throws Exception {
        String json = TextTranslationEngine.buildRequestJson("m", "zh", "hello");
        JSONObject body = new JSONObject(json);
        assertTrue(body.getJSONArray("messages").getJSONObject(0).getString("content")
                .contains("Chinese"));
    }

    @Test
    public void parseChatResponseExtractsAssistantMessage() throws Exception {
        String json = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Hello world\"}}]}";
        assertEquals("Hello world", TextTranslationEngine.parseChatResponse(json));
    }

    @Test
    public void parseChatResponseTrimsWhitespace() throws Exception {
        String json = "{\"choices\":[{\"message\":{\"content\":\"  Bonjour  \"}}]}";
        assertEquals("Bonjour", TextTranslationEngine.parseChatResponse(json));
    }

    @Test(expected = Exception.class)
    public void parseChatResponseRejectsEmptyBody() throws Exception {
        TextTranslationEngine.parseChatResponse("");
    }

    @Test(expected = Exception.class)
    public void parseChatResponseRejectsMissingChoices() throws Exception {
        TextTranslationEngine.parseChatResponse("{\"foo\":1}");
    }

    @Test(expected = Exception.class)
    public void parseChatResponseRejectsEmptyContent() throws Exception {
        TextTranslationEngine.parseChatResponse(
                "{\"choices\":[{\"message\":{\"content\":\"   \"}}]}");
    }
}
