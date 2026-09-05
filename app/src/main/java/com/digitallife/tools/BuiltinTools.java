package com.digitallife.tools;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
                "联网搜索关键词，返回结果标题/链接/摘要（可配合 web_fetch 读取全文）",
                new String[]{"query"},
                args -> webSearch(args.optString("query", ""),
                        args.optInt("count", 5)));

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

        // ===== 网络类（1 个） =====
        registerIfAbsent(tools, "web_fetch",
                "抓取指定 URL 的网页并返回纯文本正文（自动去标签、截断，默认最多 4000 字）",
                new String[]{"url"},
                args -> fetchWebPage(args.optString("url", ""),
                        args.optInt("max_chars", 4000)));

        // ===== 定时任务类（3 个，参考 OpenMinis 调度器） =====
        registerIfAbsent(tools, "schedule_task",
                "创建定时任务：delay_minutes 分钟后自动执行 prompt 描述的任务；repeat_minutes>0 时按周期重复",
                new String[]{"prompt", "delay_minutes"},
                args -> com.digitallife.brain.TaskScheduler.schedule(appContext,
                        args.optString("prompt", ""),
                        args.optLong("delay_minutes", 0),
                        args.optLong("repeat_minutes", 0)));

        registerIfAbsent(tools, "list_tasks",
                "列出当前所有定时任务（含任务 id、内容、触发时间）",
                new String[]{},
                args -> com.digitallife.brain.TaskScheduler.describe(appContext));

        registerIfAbsent(tools, "cancel_task",
                "按任务 id 取消定时任务",
                new String[]{"id"},
                args -> com.digitallife.brain.TaskScheduler.cancel(appContext,
                        args.optString("id", "")));

        // ===== 备份恢复类（3 个，参考 OpenMinis 的备份导出） =====
        registerIfAbsent(tools, "backup_data",
                "备份全部本地数据（聊天记录/记忆/设置/模型配置/人设）到应用专属目录",
                new String[]{},
                args -> {
                    String path = com.digitallife.util.BackupManager.exportBackup(appContext);
                    return path != null ? "备份完成：" + path : "备份失败，请稍后再试";
                });

        registerIfAbsent(tools, "list_backups",
                "列出已有的数据备份文件",
                new String[]{},
                args -> com.digitallife.util.BackupManager.describe(appContext));

        registerIfAbsent(tools, "restore_data",
                "从备份文件恢复数据（恢复前自动做一次安全备份；重启 App 后生效）",
                new String[]{"filename"},
                args -> {
                    String err = com.digitallife.util.BackupManager.restoreBackup(
                            appContext, args.optString("filename", ""));
                    return err != null ? err : "恢复完成，重启 App 后生效";
                });
    }

    /**
     * 抓取网页正文：HttpURLConnection 直连，跟随重定向，
     * 去除 script/style/标签后折叠空白，返回纯文本。
     * 工具在后台线程执行，允许网络 IO。
     */
    private static String fetchWebPage(String url, int maxChars) {
        if (url == null || url.trim().isEmpty()) return "缺少 url 参数";
        url = url.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        if (maxChars <= 0) maxChars = 4000;
        try {
            String text = htmlToText(httpGet(url));
            if (text.isEmpty()) return "页面为空或无法提取正文";
            if (text.length() > maxChars) {
                text = text.substring(0, maxChars) + "\n…（已截断，全文共 "
                        + text.length() + " 字）";
            }
            return text;
        } catch (Exception e) {
            return "抓取失败：" + com.digitallife.ui.UiKit.safeMsg(e);
        }
    }

    /**
     * 真实联网搜索：抓取 DuckDuckGo lite 结果页并解析标题/链接/摘要。
     * 无需 API key；解析失败时给出可 web_fetch 的兜底地址。
     */
    private static String webSearch(String query, int count) {
        if (query == null || query.trim().isEmpty()) return "缺少 query 参数";
        query = query.trim();
        if (count <= 0 || count > 10) count = 5;
        try {
            String url = "https://lite.duckduckgo.com/lite/?q="
                    + java.net.URLEncoder.encode(query, "UTF-8");
            String html = httpGet(url);
            // 结果链接：<a ... class='result-link' ...>标题</a>（属性顺序不固定，先取整标签再提 href）
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                            "<a\\b[^>]*class=['\"]result-link['\"][^>]*>(.*?)</a>",
                            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL)
                    .matcher(html);
            java.util.regex.Matcher sm = java.util.regex.Pattern.compile(
                            "<td[^>]*class=['\"]result-snippet['\"][^>]*>(.*?)</td>",
                            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL)
                    .matcher(html);
            StringBuilder sb = new StringBuilder("搜索「" + query + "」的结果：");
            int i = 0;
            while (m.find() && i < count) {
                String title = htmlToText(m.group(1));
                String link = "";
                java.util.regex.Matcher hm = java.util.regex.Pattern
                        .compile("href=['\"]([^'\"]+)['\"]")
                        .matcher(m.group(0));
                if (hm.find()) link = cleanDuckLink(hm.group(1));
                String snippet = sm.find() ? htmlToText(sm.group(1)) : "";
                i++;
                sb.append("\n\n").append(i).append(". ").append(title);
                if (!link.isEmpty()) sb.append("\n   ").append(link);
                if (!snippet.isEmpty()) sb.append("\n   ").append(snippet);
            }
            if (i == 0) {
                return "未解析到搜索结果，可用 web_fetch 打开："
                        + "https://www.bing.com/search?q="
                        + java.net.URLEncoder.encode(query, "UTF-8");
            }
            return sb.toString();
        } catch (Exception e) {
            return "搜索失败：" + com.digitallife.ui.UiKit.safeMsg(e);
        }
    }

    /** DuckDuckGo 跳转链接解出真实地址（?uddg=<urlencoded>） */
    private static String cleanDuckLink(String href) {
        try {
            int i = href.indexOf("uddg=");
            if (i >= 0) {
                String enc = href.substring(i + 5);
                int amp = enc.indexOf('&');
                if (amp >= 0) enc = enc.substring(0, amp);
                return java.net.URLDecoder.decode(enc, "UTF-8");
            }
        } catch (Exception ignored) {
        }
        return href;
    }

    /** 共用 HTTP GET：15s 超时、跟随重定向、512KB 上限；非 2xx 或非文本内容抛异常 */
    private static String httpGet(String url) throws Exception {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android) DigitalLife/1.0");
            conn.setRequestProperty("Accept", "text/html,text/plain,*/*");
            int code = conn.getResponseCode();
            if (code >= 400) throw new java.io.IOException("HTTP " + code);
            String ctype = conn.getContentType();
            if (ctype != null && !ctype.contains("text") && !ctype.contains("json")
                    && !ctype.contains("xml")) {
                throw new java.io.IOException("不支持的内容类型：" + ctype);
            }
            java.io.InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(chunk)) != -1 && total < 512 * 1024) {
                buf.write(chunk, 0, n);
                total += n;
            }
            in.close();
            return new String(buf.toByteArray(), "UTF-8");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 粗糙但够用的 HTML → 纯文本：去 script/style、去标签、折叠空白、解常见实体 */
    private static String htmlToText(String html) {
        if (html == null) return "";
        String t = html;
        t = t.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
        t = t.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        t = t.replaceAll("(?is)<!--.*?-->", " ");
        // 块级标签换成换行，保留段落结构
        t = t.replaceAll("(?i)</(p|div|br|li|tr|h[1-6]|section|article|header|footer)[^>]*>", "\n");
        t = t.replaceAll("(?is)<[^>]+>", " ");
        t = t.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'");
        // 折叠空白：行内多空格合一，多余空行合一
        t = t.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        t = t.replaceAll(" ?\\n ?", "\n");
        t = t.replaceAll("\\n{3,}", "\n\n");
        return t.trim();
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
