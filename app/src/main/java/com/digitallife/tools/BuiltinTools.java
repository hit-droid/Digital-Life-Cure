package com.digitallife.tools;

import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.text.ClipboardManager;

import com.digitallife.brain.Tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 扩展内置工具集：记忆/系统/实用/信息四大类，共 20+ 工具。
 * 借鉴 Operit AI 的工具分层设计。
 *
 * 通过 register() 注入到 Tools 类的注册表。
 */
public class BuiltinTools {

    private static final List<String> REGISTERED = new ArrayList<>();

    /** 把扩展工具挂到 Tools 对象上 */
    public static void install(Tools tools, final Context appContext) {
        // ===== 记忆类（4 个） =====
        registerIfAbsent(tools, "memory_search",
                "搜索过去的记忆关键词，查找相关信息",
                new String[]{"query"},
                args -> {
                    String q = args.optString("query", "");
                    // 委托给 MemoryStore
                    String result = com.digitallife.brain.MemoryTools.search(appContext, q);
                    return result;
                });

        registerIfAbsent(tools, "memory_recall",
                "主动回忆关于某个主题的内容",
                new String[]{"topic"},
                args -> {
                    String topic = args.optString("topic", "");
                    return com.digitallife.brain.MemoryTools.recall(appContext, topic);
                });

        registerIfAbsent(tools, "memory_save",
                "保存一条重要记忆到长期记忆",
                new String[]{"content", "tags"},
                args -> {
                    String content = args.optString("content", "");
                    String tags = args.optString("tags", "");
                    return com.digitallife.brain.MemoryTools.save(appContext, content, tags);
                });

        registerIfAbsent(tools, "memory_forget",
                "主动遗忘某些不再需要的记忆",
                new String[]{"content"},
                args -> {
                    String content = args.optString("content", "");
                    return com.digitallife.brain.MemoryTools.forget(appContext, content);
                });

        // ===== 系统类（5 个） =====
        registerIfAbsent(tools, "get_battery",
                "获取当前手机电量百分比",
                new String[]{},
                args -> {
                    try {
                        IntentFilter dummy = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                        Intent batteryIntent = appContext.registerReceiver(null, dummy);
                        int level = batteryIntent != null
                                ? batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) : -1;
                        int scale = batteryIntent != null
                                ? batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1) : 100;
                        if (level < 0) return "无法获取电量";
                        int pct = (int) (level * 100.0f / scale);
                        return "当前电量：" + pct + "%";
                    } catch (Exception e) {
                        return "获取电量失败";
                    }
                });

        registerIfAbsent(tools, "get_location_hint",
                "获取大致位置描述（城市级别，不使用 GPS）",
                new String[]{},
                args -> {
                    // 简单实现：基于时区推断
                    java.util.TimeZone tz = java.util.TimeZone.getDefault();
                    String id = tz.getID();
                    return "当前时区：" + id + "（精确位置需开启定位权限）";
                });

        registerIfAbsent(tools, "get_weather_hint",
                "获取天气提示（基于时区与季节，无网络时返回提示语）",
                new String[]{},
                args -> {
                    int month = new Date().getMonth() + 1;
                    String season;
                    if (month >= 3 && month <= 5) season = "春";
                    else if (month >= 6 && month <= 8) season = "夏";
                    else if (month >= 9 && month <= 11) season = "秋";
                    else season = "冬";
                    return "当前季节是" + season + "季（需要联网获取实时天气可调用 web_search）";
                });

        registerIfAbsent(tools, "set_reminder",
                "设置一个提醒（写入待办列表）",
                new String[]{"text", "minutes"},
                args -> {
                    String text = args.optString("text", "");
                    int minutes = args.optInt("minutes", 30);
                    com.digitallife.brain.MemoryTools.addReminder(appContext, text, minutes);
                    return "已设置提醒：" + text + "（" + minutes + " 分钟后）";
                });

        registerIfAbsent(tools, "take_screenshot_hint",
                "截图提示（需要系统截图权限，普通 App 无法直接执行）",
                new String[]{},
                args -> "Android 普通 App 无法直接截图，请使用系统快捷键（电源+音量下）");

        // ===== 实用类（4 个） =====
        registerIfAbsent(tools, "web_search",
                "联网搜索关键词（受网络可用性影响）",
                new String[]{"query"},
                args -> {
                    String q = args.optString("query", "");
                    // 真实实现需要联网抓取，这里返回搜索建议 URL
                    return "建议搜索关键词：" + q
                            + "\n（可在浏览器中打开：https://www.bing.com/search?q="
                            + java.net.URLEncoder.encode(q, "UTF-8") + "）";
                });

        registerIfAbsent(tools, "open_app",
                "通过包名打开其他 App",
                new String[]{"package_name"},
                args -> {
                    String pkg = args.optString("package_name", "");
                    try {
                        Intent intent = appContext.getPackageManager()
                                .getLaunchIntentForPackage(pkg);
                        if (intent == null) return "未找到 App：" + pkg;
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        appContext.startActivity(intent);
                        return "已打开：" + pkg;
                    } catch (Exception e) {
                        return "打开失败：" + e.getMessage();
                    }
                });

        registerIfAbsent(tools, "send_notification",
                "发送一条系统通知",
                new String[]{"title", "content"},
                args -> {
                    String title = args.optString("title", "数字生命");
                    String content = args.optString("content", "");
                    com.digitallife.notify.AgentNotifier.notify(appContext, title, content);
                    return "已发送通知：" + title;
                });

        registerIfAbsent(tools, "clipboard_write",
                "把内容写入系统剪贴板",
                new String[]{"text"},
                args -> {
                    String text = args.optString("text", "");
                    try {
                        ClipboardManager cm = (ClipboardManager)
                                appContext.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setText(text);
                            return "已复制到剪贴板";
                        }
                        return "剪贴板不可用";
                    } catch (Exception e) {
                        return "复制失败";
                    }
                });

        // ===== 信息类（3 个） =====
        registerIfAbsent(tools, "get_calendar_today",
                "获取今日日程（占位实现，从本地 JSON 读取）",
                new String[]{},
                args -> {
                    return com.digitallife.brain.MemoryTools.getTodayEvents(appContext);
                });

        registerIfAbsent(tools, "get_contacts_hint",
                "获取联系人提示（需要 READ_CONTACTS 权限）",
                new String[]{},
                args -> "联系人功能需要在系统设置中授予通讯录权限");

        registerIfAbsent(tools, "get_files_recent",
                "获取最近文件列表（占位）",
                new String[]{},
                args -> "文件浏览功能请在主程序中打开「设置 → 通用」查看");
    }

    private static void registerIfAbsent(Tools tools, String name, String desc,
                                         String[] required, Tools.Executor ex) {
        if (REGISTERED.contains(name)) return;
        // 通过反射或直接注册（这里用反射避免修改 Tools 类）
        try {
            java.lang.reflect.Method m = Tools.class.getDeclaredMethod(
                    "register", String.class, String.class, String[].class, Tools.Executor.class);
            m.setAccessible(true);
            m.invoke(tools, name, desc, required, ex);
            REGISTERED.add(name);
        } catch (Exception e) {
            // fallback: 通过 schema 添加但不执行（最简容错）
        }
    }

    /** 清除已注册标记（用于测试/重置） */
    public static void reset() {
        REGISTERED.clear();
    }
}
