package com.fieldhelper.diag.core;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Electrical calculators: Ohm's law/power, cable voltage drop, 4-20 mA scaling, unit conversion. */
public final class ElecCalc {
    private ElecCalc() {}

    private static final Pattern KEYED = Pattern.compile("\\b([VIRPvirp])\\s*=\\s*([0-9]*\\.?[0-9]+)\\s*([kmMu]?)");
    private static final Pattern UNIT = Pattern.compile("([0-9]*\\.?[0-9]+)\\s*([kmMu]?)(v|a|Ω|ohms?|w)(?![a-z])", Pattern.CASE_INSENSITIVE);

    private static double mult(String p) {
        switch (p) {
            case "k": return 1e3;
            case "M": return 1e6;
            case "m": return 1e-3;
            case "u": return 1e-6;
            default: return 1;
        }
    }

    /** Reads V, I, R, P values out of text like "V=24 R=12" or "24V 500mA". */
    public static Map<Character, Double> readValues(String q) {
        Map<Character, Double> v = new LinkedHashMap<>();
        Matcher m = KEYED.matcher(q);
        while (m.find()) v.put(Character.toUpperCase(m.group(1).charAt(0)), Double.parseDouble(m.group(2)) * mult(m.group(3)));
        m = UNIT.matcher(q);
        while (m.find()) {
            String u = m.group(3).toLowerCase(Locale.US);
            char k = u.equals("v") ? 'V' : u.equals("a") ? 'I' : u.equals("w") ? 'P' : 'R';
            if (!v.containsKey(k)) v.put(k, Double.parseDouble(m.group(1)) * mult(m.group(2)));
        }
        return v;
    }

    public static boolean isOhm(String q) { return readValues(q).size() >= 2; }

    /** True when the text is only values (plus words like "ohm", "calc"), not a question about a part. */
    public static boolean onlyValues(String q) {
        String rest = KEYED.matcher(q).replaceAll(" ");
        rest = UNIT.matcher(rest).replaceAll(" ").toLowerCase(Locale.US);
        for (String w : rest.split("[^a-z0-9]+")) {
            if (w.isEmpty()) continue;
            if (!w.matches("ohms?|law|power|calc|calculate|find|current|voltage|resistance|and|what|is|the|of|at|with|for|a|i|r|v|p")) return false;
        }
        return true;
    }

    public static Reply ohm(String q) {
        Map<Character, Double> in = readValues(q);
        Double V = in.get('V'), I = in.get('I'), R = in.get('R'), P = in.get('P');
        String how;
        if (V != null && I != null) { R = V / I; P = V * I; how = "R = V ÷ I,  P = V × I"; }
        else if (V != null && R != null) { I = V / R; P = V * I; how = "I = V ÷ R,  P = V × I"; }
        else if (V != null && P != null) { I = P / V; R = V * V / P; how = "I = P ÷ V,  R = V² ÷ P"; }
        else if (I != null && R != null) { V = I * R; P = V * I; how = "V = I × R,  P = V × I"; }
        else if (I != null && P != null) { V = P / I; R = V / I; how = "V = P ÷ I,  R = V ÷ I"; }
        else if (R != null && P != null) { I = Math.sqrt(P / R); V = I * R; how = "I = √(P ÷ R),  V = I × R"; }
        else return new Reply("Give me any two of V, I, R, P. Example: V=24 R=12  or  24V 5W");
        Reply r = new Reply("Ohm's law + power\n"
                + "V = " + eng(V, "V") + "\nI = " + eng(I, "A") + "\nR = " + eng(R, "Ω") + "\nP = " + eng(P, "W")
                + "\n\nUsed: " + how);
        r.refs.add("P-001");
        r.refs.add("P-002");
        r.kind = "calc";
        return r;
    }

    /** "drop L=20 A=1.5 I=5 V=24": copper cable, L = one-way length in metres, A = mm². */
    public static Reply drop(String q) {
        Double L = key(q, "L"), A = key(q, "A"), I = key(q, "I"), V = key(q, "V");
        if (L == null || A == null || I == null)
            return new Reply("Cable drop needs L (one-way metres), A (mm²), I (amps). Example: drop L=20 A=1.5 I=5 V=24");
        double rho = 0.0175;
        double vd = 2 * L * I * rho / A;
        StringBuilder b = new StringBuilder("Cable voltage drop (copper, out and back)\n");
        b.append("Vdrop = 2 × L × I × 0.0175 ÷ A = ").append(eng(vd, "V")).append("\n");
        b.append("Cable resistance (both wires) = ").append(eng(2 * L * rho / A, "Ω")).append("\n");
        if (V != null) {
            b.append("At the load: ").append(eng(V - vd, "V")).append("  (")
                    .append(MathEval.fmt(Math.round(vd / V * 1000) / 10.0)).append("% drop)\n");
            if (vd / V > 0.05) b.append("More than 5%: a thicker cable or shorter run may be needed.");
        }
        Reply r = new Reply(b.toString().trim());
        r.refs.add("P-006");
        r.kind = "calc";
        return r;
    }

    private static Double key(String q, String k) {
        Matcher m = Pattern.compile("\\b" + k + "\\s*=\\s*([0-9]*\\.?[0-9]+)", Pattern.CASE_INSENSITIVE).matcher(q);
        return m.find() ? Double.parseDouble(m.group(1)) : null;
    }

    /**
     * "4-20 12 0 10"      -> 12 mA on a 0..10 range = value
     * "4-20 v 5 0 10"     -> value 5 on a 0..10 range = mA
     */
    public static Reply scale(String q) {
        String t = q.toLowerCase(Locale.US).replaceFirst("^\\s*(4-20|ma)\\s*", "").replace("ma", " ").trim();
        boolean reverse = t.startsWith("v ");
        if (reverse) t = t.substring(2).trim();
        String[] p = t.split("[\\s,]+");
        if (p.length < 3)
            return new Reply("4-20 mA scaling. Examples:\n4-20 12 0 10   (12 mA on a 0-10 bar sensor → value)\n4-20 v 5 0 10   (value 5 on 0-10 → mA)");
        try {
            double x = Double.parseDouble(p[0]), lo = Double.parseDouble(p[1]), hi = Double.parseDouble(p[2]);
            String unit = p.length > 3 ? " " + p[3] : "";
            Reply r;
            if (!reverse) {
                double val = lo + (x - 4) / 16 * (hi - lo);
                String warn = x < 3.8 ? "\nBelow 3.8 mA: usually wire break or sensor fault."
                        : x > 20.5 ? "\nAbove 20.5 mA: usually short circuit or over-range." : "";
                r = new Reply(MathEval.fmt(x) + " mA on a " + MathEval.fmt(lo) + "–" + MathEval.fmt(hi) + unit + " range = "
                        + MathEval.fmt(val) + unit + "\nValue = min + (mA − 4) ÷ 16 × (max − min)" + warn);
            } else {
                double ma = 4 + (x - lo) / (hi - lo) * 16;
                r = new Reply(MathEval.fmt(x) + unit + " on a " + MathEval.fmt(lo) + "–" + MathEval.fmt(hi) + unit + " range = "
                        + MathEval.fmt(ma) + " mA\nmA = 4 + (value − min) ÷ (max − min) × 16");
            }
            r.refs.add("P-007");
            r.kind = "calc";
            return r;
        } catch (NumberFormatException e) {
            return new Reply("Couldn't read the numbers. Example: 4-20 12 0 10");
        }
    }

    private static final String[][] CONV = {
            {"bar", "psi", "14.5038"}, {"bar", "kpa", "100"}, {"bar", "mpa", "0.1"}, {"kpa", "psi", "0.145038"},
            {"kw", "hp", "1.34102"}, {"mm", "in", "0.0393701"}, {"m", "ft", "3.28084"}, {"l", "gal", "0.264172"},
            {"nm", "lbft", "0.737562"}, {"kg", "lb", "2.20462"}};

    /** "conv 5 bar psi", "conv 90 c f" */
    public static Reply convert(String q) {
        String[] p = q.toLowerCase(Locale.US).trim().split("\\s+");
        if (p.length < 4) return new Reply("Unit conversion. Example: conv 5 bar psi  or  conv 90 c f\n"
                + "Units: bar psi kpa mpa, c f, kw hp, mm in, m ft, l gal, nm lbft, kg lb");
        try {
            double x = Double.parseDouble(p[1]);
            String a = p[2], b = p[3];
            Double out = null;
            if (a.equals("c") && b.equals("f")) out = x * 9 / 5 + 32;
            else if (a.equals("f") && b.equals("c")) out = (x - 32) * 5 / 9;
            else if (a.equals(b)) out = x;
            else for (String[] c : CONV) {
                double f = Double.parseDouble(c[2]);
                if (f == 0) continue;
                if (c[0].equals(a) && c[1].equals(b)) { out = x * f; break; }
                if (c[1].equals(a) && c[0].equals(b)) { out = x / f; break; }
            }
            if (out == null) return new Reply("I don't know " + a + " → " + b + " yet. Units: bar psi kpa mpa, c f, kw hp, mm in, m ft, l gal, nm lbft, kg lb");
            Reply r = new Reply(MathEval.fmt(x) + " " + a + " = " + MathEval.fmt(out) + " " + b);
            r.kind = "calc";
            return r;
        } catch (NumberFormatException e) {
            return new Reply("Couldn't read the number. Example: conv 5 bar psi");
        }
    }

    /** 0.0047 A -> "4.7 mA" */
    public static String eng(double v, String unit) {
        double a = Math.abs(v);
        if (a == 0) return "0 " + unit;
        if (a >= 1e6) return MathEval.fmt(v / 1e6) + " M" + unit;
        if (a >= 1e3) return MathEval.fmt(v / 1e3) + " k" + unit;
        if (a >= 1) return MathEval.fmt(v) + " " + unit;
        if (a >= 1e-3) return MathEval.fmt(v * 1e3) + " m" + unit;
        return MathEval.fmt(v * 1e6) + " µ" + unit;
    }
}
