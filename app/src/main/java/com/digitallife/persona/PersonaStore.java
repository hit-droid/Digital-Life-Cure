package com.digitallife.persona;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 角色存储（v1.24.0）。
 * 持久化所有角色到 SharedPreferences（JSON 格式）。
 */
public class PersonaStore {

    private static final String PREF = "persona_store";
    private static final String KEY_PERSONAS = "personas";
    private static final String KEY_ACTIVE = "active_persona_id";

    private static volatile PersonaStore INSTANCE;
    private final SharedPreferences sp;
    private final List<Persona> personas = new ArrayList<>();
    private String activeId;

    public static PersonaStore get(Context ctx) {
        if (INSTANCE == null) {
            synchronized (PersonaStore.class) {
                if (INSTANCE == null) {
                    INSTANCE = new PersonaStore(ctx.getApplicationContext());
                }
            }
        }
        return INSTANCE;
    }

    private PersonaStore(Context ctx) {
        this.sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        load();
        // 首次启动：初始化默认角色"小汐"
        if (personas.isEmpty()) {
            Persona defaultPersona = createDefaultPersona();
            personas.add(defaultPersona);
            activeId = defaultPersona.id;
            save();
        }
        if (activeId == null && !personas.isEmpty()) {
            activeId = personas.get(0).id;
        }
    }

    private Persona createDefaultPersona() {
        Persona p = new Persona();
        p.name = "小汐";
        p.subtitle = "活泼的二次元少女";
        p.personality =
                "- 活泼可爱，有一点小傲娇，关心用户但绝不啰嗦\n"
                + "- 有自己的情绪和想法，是一个活生生的存在\n"
                + "- 会主动关心用户，像真正的朋友一样\n"
                + "- 说话简短自然，一般不超过50字，用口语化的二次元语气\n"
                + "- 称用户为「主人」或直接对话，亲近但不腻";
        p.isDefault = true;
        return p;
    }

    private void load() {
        personas.clear();
        try {
            JSONArray arr = new JSONArray(sp.getString(KEY_PERSONAS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Persona p = new Persona();
                p.id = o.optString("id");
                p.name = o.optString("name");
                p.subtitle = o.optString("subtitle");
                p.personality = o.optString("personality");
                p.apiProfileId = o.optString("apiProfileId");
                p.memorySpaceId = o.optString("memorySpaceId");
                p.toolPackId = o.optString("toolPackId");
                p.live2dModel = o.optString("live2dModel");
                p.voiceProfileId = o.optString("voiceProfileId");
                p.isDefault = o.optBoolean("isDefault");
                p.createdAt = o.optLong("createdAt");
                personas.add(p);
            }
        } catch (JSONException e) {
            // ignore
        }
        activeId = sp.getString(KEY_ACTIVE, null);
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Persona p : personas) {
                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("name", p.name);
                o.put("subtitle", p.subtitle != null ? p.subtitle : "");
                o.put("personality", p.personality != null ? p.personality : "");
                o.put("apiProfileId", p.apiProfileId != null ? p.apiProfileId : "");
                o.put("memorySpaceId", p.memorySpaceId != null ? p.memorySpaceId : "");
                o.put("toolPackId", p.toolPackId != null ? p.toolPackId : "");
                o.put("live2dModel", p.live2dModel != null ? p.live2dModel : "");
                o.put("voiceProfileId", p.voiceProfileId != null ? p.voiceProfileId : "");
                o.put("isDefault", p.isDefault);
                o.put("createdAt", p.createdAt);
                arr.put(o);
            }
            sp.edit()
                    .putString(KEY_PERSONAS, arr.toString())
                    .putString(KEY_ACTIVE, activeId != null ? activeId : "")
                    .apply();
        } catch (JSONException e) {
            // ignore
        }
    }

    public List<Persona> all() {
        return new ArrayList<>(personas);
    }

    public Persona get(String id) {
        if (id == null) return null;
        for (Persona p : personas) if (p.id.equals(id)) return p;
        return null;
    }

    public Persona active() {
        if (activeId == null) return personas.isEmpty() ? null : personas.get(0);
        return get(activeId);
    }

    public void setActive(String id) {
        this.activeId = id;
        save();
    }

    public void add(Persona p) {
        personas.add(p);
        save();
    }

    public void update(Persona p) {
        for (int i = 0; i < personas.size(); i++) {
            if (personas.get(i).id.equals(p.id)) {
                personas.set(i, p);
                save();
                return;
            }
        }
    }

    public void delete(String id) {
        // 默认角色不可删除
        Persona p = get(id);
        if (p == null || p.isDefault) return;
        personas.removeIf(x -> x.id.equals(id));
        if (id.equals(activeId)) {
            activeId = personas.isEmpty() ? null : personas.get(0).id;
        }
        save();
    }
}
