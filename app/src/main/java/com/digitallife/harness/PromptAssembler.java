package com.digitallife.harness;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

public final class PromptAssembler {

    public static final class Section {
        public final String id;
        public final String text;

        public Section(String id, String text) {
            this.id = id == null ? "" : id;
            this.text = text == null ? "" : text;
        }
    }

    private final List<Section> sections = new ArrayList<>();
    private JSONArray toolSchemas = new JSONArray();

    public synchronized void setSection(String id, String text) {
        if (id == null) return;
        for (int i = 0; i < sections.size(); i++) {
            if (id.equals(sections.get(i).id)) {
                sections.set(i, new Section(id, text));
                return;
            }
        }
        sections.add(new Section(id, text));
    }

    public synchronized String section(String id) {
        if (id == null) return "";
        for (Section s : sections) {
            if (id.equals(s.id)) return s.text;
        }
        return "";
    }

    public synchronized void setToolSchemas(JSONArray schemas) {
        this.toolSchemas = schemas != null ? schemas : new JSONArray();
    }

    public synchronized JSONArray toolSchemas() {
        return toolSchemas;
    }

    public synchronized String render() {
        StringBuilder sb = new StringBuilder();
        for (Section s : sections) {
            if (s.text == null || s.text.isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(s.text);
        }
        return sb.toString();
    }

    public synchronized String toolsDesc() {
        if (toolSchemas == null || toolSchemas.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < toolSchemas.length(); i++) {
            org.json.JSONObject t = toolSchemas.optJSONObject(i);
            if (t == null) continue;
            org.json.JSONObject fn = t.optJSONObject("function");
            if (fn == null) continue;
            sb.append("- ").append(fn.optString("name"))
                    .append(": ").append(fn.optString("description")).append("\n");
        }
        return sb.toString();
    }
}
