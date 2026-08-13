package com.leaf.hyperdragshare.codex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
    public void buildApiUrlEncodesQueryAndTarget() throws Exception {
        String url = TextTranslationEngine.buildApiUrl(
                "https://example.com/translate",
                "zh",
                "hello world");
        assertTrue(url.startsWith("https://example.com/translate?sl=auto&tl=zh&dt=t&q=hello+world"));
    }

    @Test
    public void buildApiUrlFallsBackToDefaultEndpoint() throws Exception {
        String url = TextTranslationEngine.buildApiUrl("", "en", "x");
        assertTrue(url.startsWith(TranslationSettings.DEFAULT_API_ENDPOINT + "?"));
        assertTrue(url.contains("tl=en"));
    }

    @Test
    public void buildApiUrlHandlesExistingQueryParam() throws Exception {
        String url = TextTranslationEngine.buildApiUrl(
                "https://example.com/translate?client=gtx",
                "en",
                "x");
        assertTrue(url.startsWith("https://example.com/translate?client=gtx&sl=auto"));
    }

    @Test
    public void parseApiResponseJoinsSentences() throws Exception {
        String json = "[[[\"Hello\",\"你好\",null,null,1],[\" world\",\" 世界\",null,null,1]],null,\"en\"]";
        assertEquals("Hello world", TextTranslationEngine.parseApiResponse(json));
    }

    @Test
    public void parseApiResponseHandlesSingleSentence() throws Exception {
        String json = "[[[\"Bonjour\",\"你好\",null,null,1]],null,\"fr\"]";
        assertEquals("Bonjour", TextTranslationEngine.parseApiResponse(json));
    }

    @Test(expected = Exception.class)
    public void parseApiResponseRejectsEmptyBody() throws Exception {
        TextTranslationEngine.parseApiResponse("");
    }

    @Test(expected = Exception.class)
    public void parseApiResponseRejectsMissingSentences() throws Exception {
        TextTranslationEngine.parseApiResponse("{\"foo\":1}");
    }
}
