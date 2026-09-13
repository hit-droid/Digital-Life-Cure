package com.digitallife.harness.subagent;

import com.digitallife.brain.Tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Set;

/**
 * 作用域化工具视图：只暴露白名单内的工具，其余在 schema 与执行两侧都不可见。
 * 子智能体通过它拿到最小权限，而不是拿到全部工具再在调用时判权限。
 */
public final class ScopedTools extends Tools {

    private final Tools parent;
    private final Set<String> allow;

    public ScopedTools(Tools parent, Set<String> allow) {
        super(true);
        this.parent = parent;
        this.allow = allow;
    }

    public boolean allows(String name) {
        return name != null && allow != null && allow.contains(name);
    }

    @Override
    public JSONArray toJsonArray() {
        if (parent == null) return new JSONArray();
        return parent.toJsonArray(allow);
    }

    @Override
    public String describe() {
        JSONArray schemas = toJsonArray();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < schemas.length(); i++) {
            JSONObject t = schemas.optJSONObject(i);
            if (t == null) continue;
            JSONObject fn = t.optJSONObject("function");
            if (fn == null) continue;
            sb.append("- ").append(fn.optString("name"))
                    .append(": ").append(fn.optString("description")).append("\n");
        }
        return sb.toString();
    }

    @Override
    public void execute(String name, JSONObject args, Callback cb) {
        if (!allows(name)) {
            cb.onResult(name, args, null,
                    "拒绝：该工具不在子智能体的权限范围内");
            return;
        }
        if (parent == null) {
            cb.onResult(name, args, null, "工具宿主不可用");
            return;
        }
        parent.execute(name, args, cb);
    }
}
