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
    private static final int CACHE_VERSION = 1;
    private final MiniLm model;
    private final File cacheFile;
    private final Map<String, float[]> vectors = new ConcurrentHashMap<>();
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

    /** Brings vectors in line with the cards: adds new/changed ones, drops removed ones. */
    public synchronized void sync(Collection<Card> cards, Progress p) {
        Set<String> live = new HashSet<>();
        List<Card> todo = new ArrayList<>();
        for (Card c : cards) {
            String id = c.id.toUpperCase();
            live.add(id);
            int h = cardText(c).hashCode();
            Integer old = hashes.get(id);
            if (old == null || old != h || !vectors.containsKey(id)) todo.add(c);
        }
        for (String id : new ArrayList<>(vectors.keySet())) if (!live.contains(id)) { vectors.remove(id); hashes.remove(id); }
        int done = 0;
        for (Card c : todo) {
            try {
                String t = cardText(c);
                vectors.put(c.id.toUpperCase(), model.embed(t));
                hashes.put(c.id.toUpperCase(), t.hashCode());
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
        for (Map.Entry<String, float[]> e : vectors.entrySet()) {
            float[] v = e.getValue();
            float s = 0;
            for (int d = 0; d < q.length; d++) s += q[d] * v[d];
            out.add(new Match(e.getKey(), s));
        }
        out.sort((a, b) -> Float.compare(b.similarity, a.similarity));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    private void loadCache() {
        if (cacheFile == null || !cacheFile.exists()) return;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(cacheFile)))) {
            if (in.readInt() != CACHE_VERSION) return;
            int n = in.readInt();
            Map<String, float[]> v = new HashMap<>();
            Map<String, Integer> h = new HashMap<>();
            for (int i = 0; i < n; i++) {
                String id = in.readUTF();
                int hash = in.readInt();
                float[] f = new float[MiniLm.DIM];
                for (int d = 0; d < f.length; d++) f[d] = in.readFloat();
                v.put(id, f);
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
                for (float f : vectors.get(id)) out.writeFloat(f);
            }
        } catch (Exception e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(cacheFile)) { cacheFile.delete(); tmp.renameTo(cacheFile); }
    }
}
