package com.leaf.hyperdragshare.codex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class TextTranslationEngineTest {
    private static final int OPENAI = TranslationSettings.API_TYPE_OPENAI;
    private static final int CLAUDE = TranslationSettings.API_TYPE_CLAUDE;

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
    public void buildChatUrlAppendsCompletionsForOpenAi() {
        assertEquals(
                "https://api.example.com/v1/chat/completions",
                TextTranslationEngine.buildChatUrl("https://api.example.com/v1", OPENAI));
    }

    @Test
    public void buildChatUrlHandlesTrailingSlash() {
        assertEquals(
                "https://api.example.com/v1/chat/completions",
                TextTranslationEngine.buildChatUrl("https://api.example.com/v1/", OPENAI));
    }

    @Test
    public void buildChatUrlKeepsExistingCompletionsPath() {
        assertEquals(
                "https://api.example.com/v1/chat/completions",
                TextTranslationEngine.buildChatUrl(
                        "https://api.example.com/v1/chat/completions", OPENAI));
    }

    @Test
    public void buildChatUrlFallsBackToDefaultBase() {
        assertTrue(
                TextTranslationEngine.buildChatUrl("", OPENAI)
                        .startsWith(TranslationSettings.DEFAULT_OPENAI_BASE_URL + "/chat/completions"));
    }

    @Test
    public void buildChatUrlUsesMessagesPathForClaude() {
        assertEquals(
                "https://api.anthropic.com/v1/messages",
                TextTranslationEngine.buildChatUrl("https://api.anthropic.com/v1", CLAUDE));
    }

    @Test
    public void buildChatUrlKeepsExistingMessagesPath() {
        assertEquals(
                "https://api.anthropic.com/v1/messages",
                TextTranslationEngine.buildChatUrl("https://api.anthropic.com/v1/messages", CLAUDE));
    }

    @Test
    public void buildModelsUrlAppendsModelsPath() {
        assertEquals(
                "https://api.example.com/v1/models",
                TextTranslationEngine.buildModelsUrl("https://api.example.com/v1"));
    }

    @Test
    public void buildModelsUrlKeepsExistingModelsPath() {
        assertEquals(
                "https://api.example.com/v1/models",
                TextTranslationEngine.buildModelsUrl("https://api.example.com/v1/models"));
    }

    @Test
    public void buildRequestJsonContainsModelMessagesAndTarget() throws Exception {
        String json = TextTranslationEngine.buildRequestJson(
                OPENAI, "my-model", TextTranslationEngine.defaultSystemPrompt("en"), "你好");
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
        String json = TextTranslationEngine.buildRequestJson(
                OPENAI, "m", TextTranslationEngine.defaultSystemPrompt("zh"), "hello");
        JSONObject body = new JSONObject(json);
        assertTrue(body.getJSONArray("messages").getJSONObject(0).getString("content")
                .contains("Chinese"));
    }

    @Test
    public void buildRequestJsonPassesCustomSystemPromptThrough() throws Exception {
        String json = TextTranslationEngine.buildRequestJson(
                OPENAI, "my-model", "你是医学翻译官，只输出专业术语译文。", "你好");
        JSONObject body = new JSONObject(json);
        assertEquals(
                "你是医学翻译官，只输出专业术语译文。",
                body.getJSONArray("messages").getJSONObject(0).getString("content"));
    }

    @Test
    public void buildClaudeRequestJsonHasSystemAndMaxTokens() throws Exception {
        String json = TextTranslationEngine.buildRequestJson(
                CLAUDE, "claude-x", TextTranslationEngine.defaultSystemPrompt("en"), "你好");
        JSONObject body = new JSONObject(json);
        assertEquals("claude-x", body.getString("model"));
        assertTrue(body.getString("system").contains("English"));
        assertEquals(1, body.getJSONArray("messages").length());
        assertEquals(
                "你好",
                body.getJSONArray("messages").getJSONObject(0).getString("content"));
    }

    @Test
    public void claudeRequestJsonCarriesCustomRolePrompt() throws Exception {
        String prompt = "你是法律领域翻译官，译文需符合中文法律文书用语。";
        String json = TextTranslationEngine.buildRequestJson(CLAUDE, "claude-x", prompt, "条款");
        JSONObject body = new JSONObject(json);
        assertEquals(prompt, body.getString("system"));
    }

    @Test
    public void effectiveModelFallsBackToTypeDefault() {
        TranslationSettings openAi = new TranslationSettings(
                TranslationSettings.API_TYPE_OPENAI,
                TranslationSettings.TARGET_AUTO, "", "", "", "", "");
        assertEquals(
                TranslationSettings.DEFAULT_OPENAI_MODEL, openAi.effectiveModel());
        TranslationSettings claude = new TranslationSettings(
                TranslationSettings.API_TYPE_CLAUDE,
                TranslationSettings.TARGET_AUTO, "", "", "", "", "");
        assertEquals(
                TranslationSettings.DEFAULT_CLAUDE_MODEL, claude.effectiveModel());
    }

    @Test
    public void effectiveModelKeepsUserModel() {
        TranslationSettings settings = new TranslationSettings(
                TranslationSettings.API_TYPE_OPENAI,
                TranslationSettings.TARGET_AUTO, "", "", "my-model", "", "");
        assertEquals("my-model", settings.effectiveModel());
    }

    @Test
    public void parseChatResponseExtractsAssistantMessage() throws Exception {
        String json = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Hello world\"}}]}";
        assertEquals(
                "Hello world",
                TextTranslationEngine.parseChatResponse(OPENAI, json));
    }

    @Test
    public void parseChatResponseTrimsWhitespace() throws Exception {
        String json = "{\"choices\":[{\"message\":{\"content\":\"  Bonjour  \"}}]}";
        assertEquals("Bonjour", TextTranslationEngine.parseChatResponse(OPENAI, json));
    }

    @Test
    public void parseClaudeResponseJoinsTextParts() throws Exception {
        String json = "{\"content\":[{\"type\":\"text\",\"text\":\"Hello \"},"
                + "{\"type\":\"text\",\"text\":\"world\"}]}";
        assertEquals("Hello world", TextTranslationEngine.parseChatResponse(CLAUDE, json));
    }

    @Test(expected = Exception.class)
    public void parseChatResponseRejectsEmptyBody() throws Exception {
        TextTranslationEngine.parseChatResponse(OPENAI, "");
    }

    @Test(expected = Exception.class)
    public void parseChatResponseRejectsMissingChoices() throws Exception {
        TextTranslationEngine.parseChatResponse(OPENAI, "{\"foo\":1}");
    }

    @Test(expected = Exception.class)
    public void parseChatResponseRejectsEmptyContent() throws Exception {
        TextTranslationEngine.parseChatResponse(
                OPENAI, "{\"choices\":[{\"message\":{\"content\":\"   \"}}]}");
    }

    @Test(expected = Exception.class)
    public void parseClaudeResponseRejectsMissingContent() throws Exception {
        TextTranslationEngine.parseChatResponse(CLAUDE, "{\"foo\":1}");
    }

    @Test
    public void parseModelsResponseReturnsSortedIds() throws Exception {
        String json = "{\"data\":[{\"id\":\"model-b\"},{\"id\":\"model-a\"}]}";
        assertEquals(
                Arrays.asList("model-a", "model-b"),
                TextTranslationEngine.parseModelsResponse(json));
    }

    @Test
    public void parseModelsResponseSkipsEmptyIds() throws Exception {
        String json = "{\"data\":[{\"id\":\"model-a\"},{\"type\":\"text\"}]}";
        List<String> models = TextTranslationEngine.parseModelsResponse(json);
        assertEquals(1, models.size());
        assertEquals("model-a", models.get(0));
    }

    @Test(expected = Exception.class)
    public void parseModelsResponseRejectsMissingData() throws Exception {
        TextTranslationEngine.parseModelsResponse("{\"foo\":1}");
    }

    @Test(expected = Exception.class)
    public void parseModelsResponseRejectsNoIds() throws Exception {
        TextTranslationEngine.parseModelsResponse("{\"data\":[{\"type\":\"x\"}]}");
    }
}
