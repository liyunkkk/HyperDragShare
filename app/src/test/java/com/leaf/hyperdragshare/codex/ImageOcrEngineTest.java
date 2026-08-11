package com.leaf.hyperdragshare.codex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class ImageOcrEngineTest {
    @Test
    public void noScalingWhenWithinMaxDimension() {
        Rect bounds = ImageOcrEngine.computeScaledBounds(800, 600, 1280);
        assertTrue(bounds.left == 0 && bounds.top == 0 && bounds.right == 0 && bounds.bottom == 0);
    }

    @Test
    public void scalesLargeWidthDownToMaxDimension() {
        Rect bounds = ImageOcrEngine.computeScaledBounds(2560, 1440, 1280);
        assertEquals(1280, bounds.right - bounds.left);
        assertEquals(720, bounds.bottom - bounds.top);
    }

    @Test
    public void scalesLargeHeightDownToMaxDimension() {
        Rect bounds = ImageOcrEngine.computeScaledBounds(1080, 2340, 1280);
        assertEquals(1280, bounds.bottom - bounds.top);
        assertEquals(591, bounds.right - bounds.left);
    }

    @Test
    public void preservesAspectRatio() {
        Rect bounds = ImageOcrEngine.computeScaledBounds(1920, 1080, 1280);
        assertEquals(1280, bounds.right - bounds.left);
        assertEquals(720, bounds.bottom - bounds.top);
    }

    @Test
    public void invalidInputReturnsEmptyRect() {
        assertTrue(ImageOcrEngine.computeScaledBounds(0, 100, 1280).right == 0);
        assertTrue(ImageOcrEngine.computeScaledBounds(100, -5, 1280).right == 0);
        assertTrue(ImageOcrEngine.computeScaledBounds(100, 100, 0).right == 0);
    }

    @Test
    public void squareImageKeepsSquareShape() {
        Rect bounds = ImageOcrEngine.computeScaledBounds(3000, 3000, 1280);
        assertEquals(1280, bounds.right - bounds.left);
        assertEquals(1280, bounds.bottom - bounds.top);
    }

    @Test
    public void recognitionTimeoutIsWithinReason() {
        assertTrue(ImageOcrEngine.OCR_TIMEOUT_SECONDS > 0);
        assertTrue(ImageOcrEngine.OCR_TIMEOUT_SECONDS <= 60);
    }

    @Test
    public void invalidOcrInputInvokesFailureCallback() {
        ImageOcrEngine.recognize(
                null,
                null,
                new ImageOcrEngine.Callback() {
                    @Override
                    public void onResult(String text) {
                        throw new AssertionError("invalid input must fail, not succeed");
                    }

                    @Override
                    public void onFailure(Throwable error) {
                        assertTrue(error instanceof IllegalArgumentException);
                    }
                });
    }
}
