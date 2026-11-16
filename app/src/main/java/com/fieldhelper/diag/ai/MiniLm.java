package com.fieldhelper.diag.ai;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.IOException;
import java.io.InputStream;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * The donor model: all-MiniLM-L6-v2 (Apache 2.0), a small sentence model distilled from a larger one.
 * Turns a sentence into 384 numbers that describe its meaning. Runs fully offline with ONNX Runtime.
 */
public final class MiniLm implements AutoCloseable {
    public static final int DIM = 384;
    private static final int MAX_TOKENS = 128;
    private final OrtEnvironment env;
    private final OrtSession session;
    private final WordPieceTokenizer tok;
    private final boolean hasTypeIds;

    public MiniLm(String modelPath, InputStream vocab) throws OrtException, IOException {
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setIntraOpNumThreads(4);
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        session = env.createSession(modelPath, o);
        tok = new WordPieceTokenizer(vocab);
        hasTypeIds = session.getInputNames().contains("token_type_ids");
    }

    /** Meaning vector of a text, length 1 (so dot product = cosine similarity). */
    public synchronized float[] embed(String text) throws OrtException {
        long[] ids = tok.encode(text == null ? "" : text, MAX_TOKENS);
        int n = ids.length;
        long[] mask = new long[n];
        long[] types = new long[n];
        java.util.Arrays.fill(mask, 1L);
        long[] shape = {1, n};
        Map<String, OnnxTensor> in = new HashMap<>();
        try {
            in.put("input_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape));
            in.put("attention_mask", OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape));
            if (hasTypeIds) in.put("token_type_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(types), shape));
            try (OrtSession.Result r = session.run(in)) {
                float[][][] h = (float[][][]) r.get(0).getValue(); // [1][n][384]
                float[] v = new float[DIM];
                for (int t = 0; t < n; t++) for (int d = 0; d < DIM; d++) v[d] += h[0][t][d];
                double norm = 0;
                for (int d = 0; d < DIM; d++) { v[d] /= n; norm += v[d] * v[d]; }
                norm = Math.sqrt(norm);
                if (norm > 0) for (int d = 0; d < DIM; d++) v[d] /= (float) norm;
                return v;
            }
        } finally {
            for (OnnxTensor t : in.values()) t.close();
        }
    }

    @Override
    public void close() throws OrtException { session.close(); }
}
