package com.fieldhelper.diag.ai;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** BERT uncased WordPiece tokenizer (same rules as the model was trained with). */
public final class WordPieceTokenizer {
    private final Map<String, Integer> vocab = new HashMap<>();
    private final int cls, sep, unk;

    public WordPieceTokenizer(InputStream vocabTxt) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(vocabTxt, StandardCharsets.UTF_8))) {
            String line;
            int i = 0;
            while ((line = r.readLine()) != null) vocab.put(line, i++);
        }
        cls = vocab.get("[CLS]");
        sep = vocab.get("[SEP]");
        unk = vocab.get("[UNK]");
    }

    /** Token ids with [CLS] ... [SEP], cut to maxLen. */
    public long[] encode(String text, int maxLen) {
        List<Integer> ids = new ArrayList<>();
        ids.add(cls);
        for (String w : basic(text)) {
            for (int id : wordPiece(w)) {
                if (ids.size() >= maxLen - 1) break;
                ids.add(id);
            }
            if (ids.size() >= maxLen - 1) break;
        }
        ids.add(sep);
        long[] out = new long[ids.size()];
        for (int i = 0; i < out.length; i++) out[i] = ids.get(i);
        return out;
    }

    /** Lower case, remove accents, split on spaces and punctuation. */
    static List<String> basic(String text) {
        String t = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < t.length(); ) {
            int c = t.codePointAt(i);
            i += Character.charCount(c);
            int type = Character.getType(c);
            if (type == Character.NON_SPACING_MARK) continue;
            if (c == 0 || c == 0xFFFD || (Character.isISOControl(c) && !Character.isWhitespace(c))) continue;
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) { flush(cur, out); continue; }
            if (isPunct(c) || isCjk(c)) { flush(cur, out); out.add(new String(Character.toChars(c))); continue; }
            cur.appendCodePoint(c);
        }
        flush(cur, out);
        return out;
    }

    private static void flush(StringBuilder b, List<String> out) {
        if (b.length() > 0) { out.add(b.toString()); b.setLength(0); }
    }

    private static boolean isPunct(int c) {
        if ((c >= 33 && c <= 47) || (c >= 58 && c <= 64) || (c >= 91 && c <= 96) || (c >= 123 && c <= 126)) return true;
        int t = Character.getType(c);
        return t == Character.CONNECTOR_PUNCTUATION || t == Character.DASH_PUNCTUATION || t == Character.START_PUNCTUATION
                || t == Character.END_PUNCTUATION || t == Character.INITIAL_QUOTE_PUNCTUATION || t == Character.FINAL_QUOTE_PUNCTUATION
                || t == Character.OTHER_PUNCTUATION;
    }

    private static boolean isCjk(int c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF) || (c >= 0x20000 && c <= 0x2A6DF)
                || (c >= 0x2A700 && c <= 0x2B73F) || (c >= 0x2B740 && c <= 0x2B81F) || (c >= 0x2B820 && c <= 0x2CEAF)
                || (c >= 0xF900 && c <= 0xFAFF) || (c >= 0x2F800 && c <= 0x2FA1F);
    }

    private List<Integer> wordPiece(String w) {
        List<Integer> out = new ArrayList<>();
        if (w.codePointCount(0, w.length()) > 100) { out.add(unk); return out; }
        int start = 0;
        while (start < w.length()) {
            int end = w.length();
            Integer found = null;
            while (start < end) {
                String sub = (start > 0 ? "##" : "") + w.substring(start, end);
                Integer id = vocab.get(sub);
                if (id != null) { found = id; break; }
                end--;
            }
            if (found == null) { out.clear(); out.add(unk); return out; }
            out.add(found);
            start = end;
        }
        return out;
    }
}
