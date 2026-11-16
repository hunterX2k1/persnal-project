package com.fieldhelper.diag.core;

import java.util.List;

/**
 * Finds cards by meaning instead of exact words ("module light not glowing" ≈ "H5 running lamp").
 * It only picks which cards to show; the answer text always comes from the cards themselves.
 */
public interface SemanticSearch {
    final class Match {
        public final String cardId;
        public final float similarity; // cosine, -1..1
        public Match(String cardId, float similarity) { this.cardId = cardId; this.similarity = similarity; }
    }

    /** False while the cards are still being prepared. */
    boolean ready();

    List<Match> search(String query, int limit);
}
