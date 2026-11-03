package com.docprep.app;

/** User choices for one run. */
final class Options {
    static final int IMG_NORMAL = 0, IMG_HIGH = 1, IMG_NONE = 2;
    long maxBytes = 10L * 1024 * 1024;
    long maxTokens = 150_000;
    int imageMode = IMG_NORMAL;

    /** Claude scales images down to about this long edge, so larger only wastes space. */
    static final int LONG_EDGE = 1568;
    static final int JPEG_Q = 85;

    String describe() {
        return "Settings: max part size " + (maxBytes / 1048576) + " MB, max ~" + (maxTokens / 1000)
                + "k tokens per part, images: " + (imageMode == IMG_NORMAL ? "normal" : imageMode == IMG_HIGH ? "high detail (zoom tiles)" : "text only") + ".";
    }
}
