package com.fieldhelper.diag.core;

/**
 * Calculator: + - * / ^ ( ), sqrt sin cos tan (degrees) log ln abs, pi, e,
 * and engineering suffixes on numbers: 4k7 style is not used, but 4.7k, 10m, 220u, 1M, 2G work.
 */
public final class MathEval {
    private final String s;
    private int i;

    private MathEval(String s) { this.s = s.replace(" ", "").replace("×", "*").replace("÷", "/").replace("√", "sqrt"); }

    public static double eval(String expr) {
        MathEval m = new MathEval(expr);
        double v = m.sum();
        if (m.i != m.s.length()) throw new IllegalArgumentException("Can't read '" + m.s.substring(m.i) + "'");
        return v;
    }

    /** True when the text looks like a sum to work out, not a question. */
    public static boolean looksLikeMath(String q) {
        String t = q.trim().toLowerCase();
        if (t.startsWith("=")) t = t.substring(1);
        if (t.isEmpty() || !t.matches(".*[0-9].*")) return false;
        if (!t.matches("[0-9a-z+\\-*/^().% ×÷√]+")) return false;
        // "13-14" or "4-20" looks like a terminal or range, not a sum (type =13-14 to force maths).
        if (!q.trim().startsWith("=") && t.matches("[0-9.]+-[0-9.]+")) return false;
        String rest = t.replaceAll("sqrt|sin|cos|tan|log|ln|abs|pi", "").replaceAll("[0-9.]+(e-?[0-9]+)?[kmugn]?", "");
        if (!rest.replaceAll("[+\\-*/^()% ×÷√e]", "").isEmpty()) return false;
        return t.matches(".*[+*/^(×÷√].*") || t.matches(".*[0-9)]\\s*-.*") || t.matches(".*(sqrt|sin|cos|tan|log|ln|abs).*");
    }

    private double sum() {
        double v = product();
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '+') { i++; v += product(); }
            else if (c == '-') { i++; v -= product(); }
            else break;
        }
        return v;
    }

    private double product() {
        double v = power();
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '*') { i++; v *= power(); }
            else if (c == '/') { i++; v /= power(); }
            else break;
        }
        return v;
    }

    private double power() {
        double b = unary();
        if (i < s.length() && s.charAt(i) == '^') { i++; return Math.pow(b, power()); }
        return b;
    }

    private double unary() {
        if (i < s.length() && s.charAt(i) == '-') { i++; return -unary(); }
        if (i < s.length() && s.charAt(i) == '+') { i++; return unary(); }
        return atom();
    }

    private double atom() {
        if (i >= s.length()) throw new IllegalArgumentException("Expression ends too early");
        char c = s.charAt(i);
        if (c == '(') {
            i++;
            double v = sum();
            if (i >= s.length() || s.charAt(i) != ')') throw new IllegalArgumentException("Missing )");
            i++;
            return v;
        }
        if (Character.isDigit(c) || c == '.') return number();
        String[] fns = {"sqrt", "sin", "cos", "tan", "log", "ln", "abs"};
        String low = s.substring(i).toLowerCase();
        for (String f : fns) {
            if (low.startsWith(f)) {
                i += f.length();
                double a = atom();
                switch (f) {
                    case "sqrt": return Math.sqrt(a);
                    case "sin": return Math.sin(Math.toRadians(a));
                    case "cos": return Math.cos(Math.toRadians(a));
                    case "tan": return Math.tan(Math.toRadians(a));
                    case "log": return Math.log10(a);
                    case "ln": return Math.log(a);
                    default: return Math.abs(a);
                }
            }
        }
        if (low.startsWith("pi")) { i += 2; return Math.PI; }
        if (low.startsWith("e")) { i += 1; return Math.E; }
        throw new IllegalArgumentException("Can't read '" + s.substring(i) + "'");
    }

    private double number() {
        int st = i;
        while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
        if (i < s.length() - 1 && (s.charAt(i) == 'e' || s.charAt(i) == 'E')
                && (Character.isDigit(s.charAt(i + 1)) || s.charAt(i + 1) == '-')) {
            i++;
            if (s.charAt(i) == '-') i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        }
        double v = Double.parseDouble(s.substring(st, i));
        if (i < s.length()) {
            char c = s.charAt(i);
            double m = 0;
            switch (c) {
                case 'k': case 'K': m = 1e3; break;
                case 'M': m = 1e6; break;
                case 'G': m = 1e9; break;
                case 'm': m = 1e-3; break;
                case 'u': case 'µ': m = 1e-6; break;
                case 'n': m = 1e-9; break;
            }
            if (m != 0) { i++; v *= m; }
        }
        if (i < s.length() && s.charAt(i) == '%') { i++; v /= 100; }
        return v;
    }

    public static String fmt(double v) {
        if (Double.isNaN(v)) return "not a number";
        if (Double.isInfinite(v)) return "infinite (divide by zero?)";
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
        String r = String.format(java.util.Locale.US, "%.6g", v);
        if (r.contains("e")) return r;
        if (r.contains(".")) r = r.replaceAll("0+$", "").replaceAll("\\.$", "");
        return r;
    }
}
