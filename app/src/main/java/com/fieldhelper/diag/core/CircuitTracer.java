package com.fieldhelper.diag.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks a circuit path and builds a check list, the way an engineer would:
 * load first, then supply at the load, then step back toward the source, then the return path.
 */
public final class CircuitTracer {
    private CircuitTracer() {}

    public static List<String> nodes(String path) {
        List<String> r = new ArrayList<>();
        if (path == null) return r;
        String p = path.replace("→", ">").replace("->", ">");
        for (String n : p.split(">")) if (!n.trim().isEmpty()) r.add(n.trim());
        return r;
    }

    /** Index of the load in the path: given name, else the node just before the return side. */
    public static int loadIndex(List<String> n, String load) {
        if (load != null && !load.trim().isEmpty()) {
            String l = load.trim().toUpperCase();
            for (int k = 0; k < n.size(); k++) {
                String u = n.get(k).toUpperCase();
                if (u.equals(l) || u.startsWith(l + ":") || u.startsWith(l + " ")) return k;
            }
        }
        // Guess: last node that is not a terminal/ground on the way back.
        for (int k = n.size() - 2; k > 0; k--) {
            String u = n.get(k).toUpperCase();
            if (!isTerminal(u) && !isGround(u)) return k;
        }
        return Math.max(0, n.size() - 2);
    }

    static boolean isGround(String u) {
        return u.equals("0V") || u.startsWith("GND") || u.startsWith("PE") || u.contains("GROUND") || u.contains("EARTH") || u.equals("-");
    }

    static boolean isTerminal(String u) {
        return u.startsWith("X") || u.startsWith("TB") || u.startsWith("J") && u.contains(":");
    }

    /** What to check for one part, based on its usual letter code. */
    public static String hint(String node) {
        String u = node.toUpperCase();
        String name = node;
        if (isGround(u)) return "Return " + name + ": check ground continuity back to 0V (should be near 0 Ω).";
        if (u.matches("^[0-9.]+ ?V.*") || u.startsWith("+") || u.startsWith("L+") || u.startsWith("BAT"))
            return "Supply " + name + ": measure it against 0V. Is the battery or power supply OK?";
        if (u.startsWith("F")) return "Fuse " + name + ": power off, check continuity. Blown fuse? Find why before replacing.";
        if (u.startsWith("Q")) return "Breaker " + name + ": is it ON or tripped? Measure voltage in and out.";
        if (u.startsWith("K")) return "Relay contact " + name + ": is the relay pulled in? Measure voltage both sides of the contact.";
        if (u.startsWith("S")) return "Switch " + name + ": operate it and measure voltage both sides.";
        if (u.startsWith("X") || u.startsWith("TB")) return "Terminal " + name + ": measure voltage here, check the wire is tight and not corroded.";
        if (u.startsWith("H")) return "Lamp " + name + ": check the bulb/LED (swap with a known good one).";
        if (u.startsWith("M")) return "Motor " + name + ": check winding resistance and that it turns freely.";
        if (u.startsWith("Y")) return "Solenoid/valve " + name + ": check coil resistance against spec.";
        if (u.startsWith("B")) return "Sensor " + name + ": check supply, signal and ground at its connector.";
        if (u.startsWith("A") || u.contains("PLC") || u.contains("DO") || u.contains("DI"))
            return "Module " + name + ": is the output/input active? Check status LED or diagnostic screen.";
        if (u.startsWith("W") || u.startsWith("CABLE")) return "Cable " + name + ": check continuity and insulation.";
        return "Point " + name + ": measure voltage here.";
    }

    /** Builds the ordered check list for a circuit path. */
    public static List<String> checks(String path, String load, String control) {
        List<String> out = new ArrayList<>();
        List<String> n = nodes(path);
        if (n.size() < 2) return out;
        int li = loadIndex(n, load);
        String loadName = n.get(li);
        out.add(hint(loadName));
        if (li > 0) {
            out.add("Measure voltage at the input of " + loadName + " (at " + n.get(li - 1) + ") against 0V. "
                    + "Voltage present → the fault is the load or its return path. No voltage → go back toward the supply.");
        }
        for (int k = li - 1; k >= 0; k--) {
            String h = hint(n.get(k));
            if (n.get(k).toUpperCase().startsWith("K") && control != null && !control.trim().isEmpty())
                h += " It is switched by: " + control.trim() + ".";
            out.add(h);
        }
        if (li < n.size() - 1) {
            StringBuilder ret = new StringBuilder();
            for (int k = li + 1; k < n.size(); k++) {
                if (ret.length() > 0) ret.append(" → ");
                ret.append(n.get(k));
            }
            out.add("Return path " + ret + ": check continuity from " + loadName + " to 0V.");
        }
        return out;
    }

    /** Short "it works when..." sentence. */
    public static String worksWhen(String path, String load, String control) {
        List<String> n = nodes(path);
        if (n.isEmpty()) return "";
        int li = loadIndex(n, load);
        StringBuilder b = new StringBuilder();
        b.append(n.get(li)).append(" works when current flows: ").append(String.join(" → ", n)).append(".");
        if (control != null && !control.trim().isEmpty()) b.append(" Switched by ").append(control.trim()).append(".");
        return b.toString();
    }
}
