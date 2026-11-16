package com.fieldhelper.diag.ai;

import com.fieldhelper.diag.core.Card;
import com.fieldhelper.diag.core.SemanticSearch;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps one meaning vector per card, saved to a cache file so each card is only processed once.
 */
public final class SemanticIndex implements SemanticSearch {
    private static final int CACHE_VERSION = 2;
    /** Measured on a real MTU sheet set: true heading matches 0.69-0.78, noise up to 0.51. */
    private static final float HEADING_MIN = 0.6f;
    private final MiniLm model;
    private final File cacheFile;
    private final Map<String, float[][]> vectors = new ConcurrentHashMap<>();
    private final Map<String, Integer> hashes = new ConcurrentHashMap<>();
    private volatile boolean ready = false;

    public interface Progress { void update(int done, int total); }

    public SemanticIndex(MiniLm model, File cacheFile) {
        this.model = model;
        this.cacheFile = cacheFile;
        loadCache();
    }

    @Override public boolean ready() { return ready; }

    /** The text that describes a card's meaning. */
    public static String cardText(Card c) {
        StringBuilder b = new StringBuilder(c.title);
        if (!c.tags.isEmpty()) b.append(". ").append(String.join(", ", c.tags));
        String s = c.simple == null ? "" : c.simple;
        if (!s.startsWith("Read automatically") && !s.startsWith("Drawing read")) {
            int dot = s.indexOf(". ");
            b.append(". ").append(dot > 0 && dot < 300 ? s.substring(0, dot + 1) : s.length() > 300 ? s.substring(0, 300) : s);
        }
        return b.toString();
    }

    /**
     * All texts to embed for a card. Scanned sheets mix many topics, so each heading gets its own
     * vector too, and the best-matching one counts.
     */
    public static List<String> cardTexts(Card c) {
        List<String> r = new ArrayList<>();
        r.add(cardText(c));
        if (c.id.toUpperCase().matches("^DOC-.+-P\\d+$")) {
            int a = c.title.indexOf(" (");
            int z = c.title.lastIndexOf(')');
            if (a > 0 && z > a) {
                for (String h : c.title.substring(a + 2, z).replace("…", "").split(",")) {
                    String t = h.trim();
                    if (t.length() > 2 && r.size() < 25) r.add(t);
                }
            }
        }
        return r;
    }

    /** Brings vectors in line with the cards: adds new/changed ones, drops removed ones. */
    public synchronized void sync(Collection<Card> cards, Progress p) {
        Set<String> live = new HashSet<>();
        List<Card> todo = new ArrayList<>();
        for (Card c : cards) {
            String id = c.id.toUpperCase();
            live.add(id);
            int h = String.join("\n", cardTexts(c)).hashCode();
            Integer old = hashes.get(id);
            if (old == null || old != h || !vectors.containsKey(id)) todo.add(c);
        }
        for (String id : new ArrayList<>(vectors.keySet())) if (!live.contains(id)) { vectors.remove(id); hashes.remove(id); }
        int done = 0;
        for (Card c : todo) {
            try {
                List<String> ts = cardTexts(c);
                float[][] vs = new float[ts.size()][];
                for (int k = 0; k < ts.size(); k++) vs[k] = model.embed(ts.get(k));
                vectors.put(c.id.toUpperCase(), vs);
                hashes.put(c.id.toUpperCase(), String.join("\n", ts).hashCode());
            } catch (Exception ignored) { }
            done++;
            if (p != null && (done % 10 == 0 || done == todo.size())) p.update(done, todo.size());
        }
        if (!todo.isEmpty() || vectors.size() != live.size()) saveCache();
        ready = true;
    }

    @Override
    public List<Match> search(String query, int limit) {
        List<Match> out = new ArrayList<>();
        if (!ready) return out;
        float[] q;
        try { q = model.embed(query); } catch (Exception e) { return out; }
        for (Map.Entry<String, float[][]> e : vectors.entrySet()) {
            float[][] vs = e.getValue();
            float full = dot(q, vs[0]), head = -1;
            for (int k = 1; k < vs.length; k++) head = Math.max(head, dot(q, vs[k]));
            // a single heading only counts when it clearly matches (short texts give noisy scores);
            // the whole-card score then breaks ties between sheets with the same heading
            float sim = head >= HEADING_MIN ? Math.max(full, head + 0.1f * full) : full;
            out.add(new Match(e.getKey(), sim));
        }
        out.sort((a, b) -> Float.compare(b.similarity, a.similarity));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    private static float dot(float[] a, float[] b) {
        float s = 0;
        for (int d = 0; d < a.length; d++) s += a[d] * b[d];
        return s;
    }

    private void loadCache() {
        if (cacheFile == null || !cacheFile.exists()) return;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(cacheFile)))) {
            if (in.readInt() != CACHE_VERSION) return;
            int n = in.readInt();
            Map<String, float[][]> v = new HashMap<>();
            Map<String, Integer> h = new HashMap<>();
            for (int i = 0; i < n; i++) {
                String id = in.readUTF();
                int hash = in.readInt();
                int count = in.readInt();
                float[][] fs = new float[count][MiniLm.DIM];
                for (float[] f : fs) for (int d = 0; d < f.length; d++) f[d] = in.readFloat();
                v.put(id, fs);
                h.put(id, hash);
            }
            vectors.putAll(v);
            hashes.putAll(h);
        } catch (Exception ignored) {
            vectors.clear();
            hashes.clear();
        }
    }

    private void saveCache() {
        if (cacheFile == null) return;
        File tmp = new File(cacheFile.getPath() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            out.writeInt(CACHE_VERSION);
            List<String> ids = new ArrayList<>(vectors.keySet());
            out.writeInt(ids.size());
            for (String id : ids) {
                out.writeUTF(id);
                out.writeInt(hashes.getOrDefault(id, 0));
                float[][] fs = vectors.get(id);
                out.writeInt(fs.length);
                for (float[] f : fs) for (float x : f) out.writeFloat(x);
            }
        } catch (Exception e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(cacheFile)) { cacheFile.delete(); tmp.renameTo(cacheFile); }
    }
}
