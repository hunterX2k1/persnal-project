package com.fieldhelper.diag.core;

import java.util.ArrayList;
import java.util.List;

/** An answer plus the card IDs / source pages it came from. */
public class Reply {
    public final String text;
    public final List<String> refs = new ArrayList<>();
    /** "Fault", "Circuit", "calc", "math", "none", "help"... */
    public String kind = "";

    public Reply(String text) { this.text = text; }

    public String refLine() {
        return refs.isEmpty() ? "" : "[" + String.join(" · ", refs) + "]";
    }
}
