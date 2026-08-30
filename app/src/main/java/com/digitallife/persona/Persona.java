package com.digitallife.persona;

/**
 * 角色卡（v1.24.0）。
 * 借鉴 Operit AI 的 per-character binding 设计。
 * 每个角色独立绑定：人格、API 配置、记忆空间、工具集、Live2D 模型、语音。
 */
public class Persona {
    public String id;             // 唯一 id（uuid-like）
    public String name;           // 角色名（如"小汐"）
    public String subtitle;       // 副标题（如"活泼的二次元少女"）
    public String personality;    // 人格描述（注入 system prompt）
    public String apiProfileId;   // 绑定的 API 配置 id
    public String memorySpaceId;  // 独立记忆空间 id
    public String toolPackId;     // 工具包 id
    public String live2dModel;    // Live2D 模型路径
    public String voiceProfileId; // 语音配置 id
    public boolean isDefault;     // 是否默认角色
    public long createdAt;

    public Persona() {
        this.id = java.util.UUID.randomUUID().toString();
        this.createdAt = System.currentTimeMillis();
    }

    /** 渲染为可注入 system prompt 的人格段 */
    public String toPromptSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("你的名字是「").append(name).append("」\n");
        if (subtitle != null && !subtitle.isEmpty()) {
            sb.append("定位：").append(subtitle).append("\n");
        }
        if (personality != null && !personality.isEmpty()) {
            sb.append("## 性格\n").append(personality).append("\n");
        }
        return sb.toString();
    }
}
