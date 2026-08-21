package com.leaf.hyperdragshare.codex;

import android.content.Context;

/**
 * Values consumed by the imported BigBang word-chip core. DragShare does not expose the
 * reference app's separate search-settings surface, so the original defaults are retained.
 */
final class BigBangSettings {
    private static final BigBangSettings INSTANCE = new BigBangSettings();

    private BigBangSettings() {}

    static BigBangSettings get(Context context) {
        return INSTANCE;
    }

    int getGapRowHeightPercent() {
        // [Layout optimization] Reduced from 40% to 25% for tighter paragraph spacing.
        return 25;
    }

    int getWebSearchType() {
        return BoomActionHandler.SEARCH_WEB;
    }

    int getDictSearchType() {
        return BoomActionHandler.SEARCH_DICTIONARY;
    }
}
