package com.digitallife.ui.chat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * v1.188.0：代码块语法高亮。
 *
 * <p>只做「按语言切词」——把代码切成 {@link Token} 区间并标出类型（关键字 / 字符串 /
 * 注释 / 数字 / 类型名），颜色由 {@link com.digitallife.ui.MarkdownRenderer} 决定。
 * 与 Android 无关，可在普通 JVM 单测里跑。</p>
 *
 * <p>为什么不用现成的高亮库：项目约定不引第三方依赖，而聊天气泡里的代码块
 * 只需要「看起来有结构」；一个 O(n) 字符扫描足够，且能精确控制 token 边界，
 * 不会像正则方案那样在嵌套注释/字符串转义上翻车。</p>
 */
public final class CodeHighlighter {

    /** token 类型；渲染层据此选颜色 */
    public enum Kind { KEYWORD, STRING, COMMENT, NUMBER, TYPE }

    /** 一段带类型的字符区间，坐标相对代码块正文 */
    public static final class Token {
        public final int start;
        public final int end;
        public final Kind kind;

        Token(int start, int end, Kind kind) {
            this.start = start;
            this.end = end;
            this.kind = kind;
        }

        @Override
        public String toString() {
            return kind + "[" + start + "," + end + ")";
        }
    }

    public static final String JAVA = "java";
    public static final String KOTLIN = "kotlin";
    public static final String JS = "js";
    public static final String PYTHON = "python";
    public static final String SHELL = "shell";
    public static final String SQL = "sql";
    public static final String C = "c";
    public static final String GO = "go";
    public static final String RUST = "rust";
    public static final String XML = "xml";
    public static final String JSON = "json";
    public static final String YAML = "yaml";
    public static final String CSS = "css";
    public static final String GENERIC = "generic";

    private static final Set<String> KEYWORDS_JAVA = set(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new",
            "package", "private", "protected", "public", "record", "return", "sealed",
            "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "var", "void", "volatile", "while",
            "yield", "true", "false", "null");

    private static final Set<String> KEYWORDS_KOTLIN = set(
            "abstract", "actual", "annotation", "as", "break", "by", "catch", "class",
            "companion", "const", "constructor", "continue", "crossinline", "data",
            "do", "else", "enum", "expect", "external", "false", "final", "finally",
            "for", "fun", "get", "if", "import", "in", "init", "inline", "inner",
            "interface", "internal", "is", "lateinit", "lazy", "noinline", "null",
            "object", "open", "operator", "out", "override", "package", "private",
            "protected", "public", "reified", "return", "sealed", "set", "super",
            "suspend", "tailrec", "this", "throw", "true", "try", "typealias", "val",
            "var", "vararg", "when", "where", "while", "yield");

    private static final Set<String> KEYWORDS_JS = set(
            "async", "await", "break", "case", "catch", "class", "const", "continue",
            "debugger", "declare", "default", "delete", "do", "else", "enum", "export",
            "extends", "false", "finally", "for", "from", "function", "get", "if",
            "implements", "import", "in", "instanceof", "interface", "let", "namespace",
            "new", "null", "of", "private", "protected", "public", "readonly", "return",
            "set", "static", "super", "switch", "this", "throw", "true", "try", "type",
            "typeof", "undefined", "var", "void", "while", "with", "yield");

    private static final Set<String> KEYWORDS_PYTHON = set(
            "False", "None", "True", "and", "as", "assert", "async", "await", "break",
            "class", "continue", "def", "del", "elif", "else", "except", "finally",
            "for", "from", "global", "if", "import", "in", "is", "lambda", "match",
            "nonlocal", "not", "or", "pass", "raise", "return", "self", "try", "while",
            "with", "yield");

    /** SQL 关键字统一按大写存，匹配时把词转大写 */
    private static final Set<String> KEYWORDS_SQL = set(
            "ADD", "ALL", "ALTER", "AND", "AS", "ASC", "BEGIN", "BETWEEN", "BY", "CASE",
            "CAST", "CHECK", "COLUMN", "COMMIT", "CONSTRAINT", "CREATE", "CROSS",
            "DATABASE", "DEFAULT", "DELETE", "DESC", "DISTINCT", "DROP", "ELSE", "END",
            "EXISTS", "FOREIGN", "FROM", "FULL", "GROUP", "HAVING", "IF", "IN", "INDEX",
            "INNER", "INSERT", "INTO", "IS", "JOIN", "KEY", "LEFT", "LIKE", "LIMIT",
            "NOT", "NULL", "OFFSET", "ON", "OR", "ORDER", "OUTER", "PRIMARY",
            "REFERENCES", "RIGHT", "ROLLBACK", "SELECT", "SET", "TABLE", "THEN",
            "TRANSACTION", "UNION", "UNIQUE", "UPDATE", "VALUES", "VIEW", "WHEN",
            "WHERE", "WITH");

    private static final Set<String> KEYWORDS_SHELL = set(
            "break", "case", "continue", "do", "done", "elif", "else", "esac", "export",
            "fi", "for", "function", "if", "in", "local", "readonly", "return", "select",
            "then", "time", "until", "while");

    private static final Set<String> KEYWORDS_C = set(
            "auto", "break", "case", "char", "class", "const", "continue", "default",
            "delete", "do", "double", "else", "enum", "extern", "false", "float", "for",
            "goto", "if", "inline", "int", "long", "namespace", "new", "nullptr",
            "private", "protected", "public", "register", "return", "short", "signed",
            "sizeof", "static", "struct", "switch", "template", "true", "typedef",
            "union", "unsigned", "using", "virtual", "void", "volatile", "while");

    private static final Set<String> KEYWORDS_GO = set(
            "break", "case", "chan", "const", "continue", "default", "defer", "else",
            "fallthrough", "false", "for", "func", "go", "goto", "if", "import",
            "interface", "map", "nil", "package", "range", "return", "select", "struct",
            "switch", "true", "type", "var");

    private static final Set<String> KEYWORDS_RUST = set(
            "Self", "as", "async", "await", "break", "const", "continue", "crate", "dyn",
            "else", "enum", "extern", "false", "fn", "for", "if", "impl", "in", "let",
            "loop", "match", "mod", "move", "mut", "pub", "ref", "return", "self",
            "static", "struct", "super", "trait", "true", "type", "unsafe", "use",
            "where", "while");

    private static final Set<String> KEYWORDS_JSON = set("true", "false", "null");

    private static final Set<String> KEYWORDS_YAML =
            set("true", "false", "null", "yes", "no", "on", "off");

    /** Java/Kotlin/JS 常见类型名；配合「首字母大写」启发式使用 */
    private static final Set<String> TYPES_COMMON = set(
            "String", "Integer", "Long", "Double", "Float", "Boolean", "Byte", "Short",
            "Character", "Object", "List", "Map", "Set", "ArrayList", "HashMap",
            "HashSet", "Exception", "RuntimeException", "Thread", "Runnable", "System",
            "Math", "Arrays", "Collections", "Optional", "StringBuilder", "Override",
            "JSONObject", "JSONArray", "View", "Context", "Activity", "Bitmap");

    /** Python 小写内建类型 */
    private static final Set<String> TYPES_PYTHON = set(
            "bool", "bytes", "dict", "float", "int", "list", "object", "set", "str", "tuple");

    private CodeHighlighter() {
    }

    /**
     * 把代码切成带类型的区间。
     *
     * @param code 代码块正文（不含 ``` 围栏）
     * @param info 围栏后的语言标注（如 {@code "java"}、{@code "python {linenos=true}"}），
     *             为空时按内容猜
     * @return 按起点升序、互不重叠的 token 列表
     */
    public static List<Token> highlight(String code, String info) {
        if (code == null || code.isEmpty()) return Collections.emptyList();
        String family = resolveFamily(code, info);
        Set<String> keywords = keywordsOf(family);
        List<Token> out = new ArrayList<>();
        int n = code.length();
        int i = 0;
        while (i < n) {
            char c = code.charAt(i);

            // ---- 注释 ----
            if (hasBlockComment(family) && startsWith(code, i, "/*")) {
                int end = code.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                out.add(new Token(i, end, Kind.COMMENT));
                i = end;
                continue;
            }
            if (family.equals(XML) && startsWith(code, i, "<!--")) {
                int end = code.indexOf("-->", i + 4);
                end = end < 0 ? n : end + 3;
                out.add(new Token(i, end, Kind.COMMENT));
                i = end;
                continue;
            }
            String lineMark = lineCommentMark(family);
            if (lineMark != null && startsWith(code, i, lineMark)
                    && commentBoundaryOk(code, i, lineMark)) {
                int end = code.indexOf('\n', i);
                end = end < 0 ? n : end;
                out.add(new Token(i, end, Kind.COMMENT));
                i = end;
                continue;
            }

            // ---- 字符串 ----
            if (family.equals(PYTHON) && (startsWith(code, i, "\"\"\"")
                    || startsWith(code, i, "'''"))) {
                String q = code.substring(i, i + 3);
                int end = code.indexOf(q, i + 3);
                end = end < 0 ? n : end + 3;
                out.add(new Token(i, end, Kind.STRING));
                i = end;
                continue;
            }
            if (c == '"' || c == '\'') {
                int end = scanQuoted(code, i, c);
                out.add(new Token(i, end, Kind.STRING));
                i = end;
                continue;
            }
            if (c == '`' && hasBacktickString(family)) {
                int end = scanQuoted(code, i, '`');
                out.add(new Token(i, end, Kind.STRING));
                i = end;
                continue;
            }

            // ---- 数字 ----
            if (isDigit(c) && !(i > 0 && isIdentPart(code.charAt(i - 1)))) {
                int end = scanNumber(code, i);
                out.add(new Token(i, end, Kind.NUMBER));
                i = end;
                continue;
            }

            // ---- 标识符 / 关键字 / 类型 ----
            if (isIdentStart(c)) {
                int end = i + 1;
                while (end < n && isIdentPart(code.charAt(end))) end++;
                String word = code.substring(i, end);
                if (keywords.contains(keywordKey(word, family))) {
                    out.add(new Token(i, end, Kind.KEYWORD));
                } else if (isTypeName(word, family)) {
                    out.add(new Token(i, end, Kind.TYPE));
                }
                i = end;
                continue;
            }
            i++;
        }
        return out;
    }

    /** 仅按围栏语言判断家族；认不出返回 {@link #GENERIC}（不做内容猜测） */
    public static String familyOf(String info) {
        String token = fenceToken(info);
        if (token.isEmpty()) return GENERIC;
        switch (token) {
            case "java": case "jav": case "gradle":
                return JAVA;
            case "kotlin": case "kt": case "kts":
                return KOTLIN;
            case "js": case "javascript": case "mjs": case "cjs": case "node":
            case "jsx": case "ts": case "typescript": case "tsx":
                return JS;
            case "python": case "py": case "python3": case "py3":
                return PYTHON;
            case "sh": case "bash": case "zsh": case "shell": case "shellscript":
            case "console": case "terminal":
                return SHELL;
            case "sql": case "mysql": case "postgres": case "postgresql": case "psql":
            case "sqlite": case "sqlite3":
                return SQL;
            case "c": case "h": case "cc": case "cpp": case "c++": case "cxx":
            case "objc": case "objective-c":
                return C;
            case "go": case "golang":
                return GO;
            case "rust": case "rs":
                return RUST;
            case "xml": case "html": case "htm": case "svg":
                return XML;
            case "json": case "jsonc":
                return JSON;
            case "yaml": case "yml":
                return YAML;
            case "css": case "scss": case "less":
                return CSS;
            default:
                return GENERIC;
        }
    }

    private static String resolveFamily(String code, String info) {
        String family = familyOf(info);
        if (!family.equals(GENERIC)) return family;
        return guessFamily(code);
    }

    /** 围栏没写语言时按内容猜（模型偷懒时也能有高亮） */
    private static String guessFamily(String code) {
        String head = code.trim();
        if (head.isEmpty()) return GENERIC;
        String lower = head.toLowerCase(Locale.ROOT);
        if (head.startsWith("<?xml") || lower.startsWith("<!doctype")
                || lower.startsWith("<html")) {
            return XML;
        }
        if (head.startsWith("#!")) return SHELL;
        if (lower.startsWith("package ") || lower.startsWith("import java")
                || lower.contains("public class ") || lower.contains("public static void main")) {
            return JAVA;
        }
        if (lower.startsWith("fun ") || lower.startsWith("val ") || lower.startsWith("internal ")) {
            return KOTLIN;
        }
        if (lower.startsWith("def ") || lower.startsWith("from ") || lower.startsWith("import ")
                || (lower.startsWith("class ") && head.contains(":"))) {
            return PYTHON;
        }
        if (lower.startsWith("select ") || lower.startsWith("insert ") || lower.startsWith("update ")
                || lower.startsWith("delete ") || lower.startsWith("create table ")) {
            return SQL;
        }
        if (head.startsWith("{") || head.startsWith("[")) return JSON;
        if (lower.startsWith("fn ") || lower.startsWith("impl ") || lower.startsWith("use ")) {
            return RUST;
        }
        if (lower.startsWith("func ") || lower.startsWith("package main")) return GO;
        if (lower.startsWith("function ") || lower.startsWith("const ") || lower.startsWith("let ")
                || lower.startsWith("export ")) {
            return JS;
        }
        if (lower.startsWith("#include") || lower.contains("int main(")) return C;
        return GENERIC;
    }

    private static String fenceToken(String info) {
        if (info == null) return "";
        String s = info.trim().toLowerCase(Locale.ROOT);
        // ```java {linenos=table} / ```java,title / ```language-java
        int cut = s.length();
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (Character.isWhitespace(c) || c == ',' || c == '{' || c == '}' || c == ';') {
                cut = k;
                break;
            }
        }
        s = s.substring(0, cut);
        if (s.startsWith("language-")) s = s.substring("language-".length());
        if (s.startsWith(".")) s = s.substring(1);
        return s;
    }

    private static Set<String> keywordsOf(String family) {
        switch (family) {
            case JAVA: return KEYWORDS_JAVA;
            case KOTLIN: return KEYWORDS_KOTLIN;
            case JS: return KEYWORDS_JS;
            case PYTHON: return KEYWORDS_PYTHON;
            case SQL: return KEYWORDS_SQL;
            case SHELL: return KEYWORDS_SHELL;
            case C: return KEYWORDS_C;
            case GO: return KEYWORDS_GO;
            case RUST: return KEYWORDS_RUST;
            case JSON: return KEYWORDS_JSON;
            case YAML: return KEYWORDS_YAML;
            default: return Collections.emptySet();
        }
    }

    private static String keywordKey(String word, String family) {
        return family.equals(SQL) ? word.toUpperCase(Locale.ROOT) : word;
    }

    private static boolean isTypeName(String word, String family) {
        if (TYPES_COMMON.contains(word)) return true;
        if (family.equals(PYTHON)) return TYPES_PYTHON.contains(word);
        // 首字母大写按类型名着色：Java/Go/Rust/Python 的类名习惯如此；
        // shell/sql/yaml/json/css 的标识符大小写不代表类型，不做这个猜测
        if (Character.isUpperCase(word.charAt(0)) && !family.equals(SHELL)
                && !family.equals(SQL) && !family.equals(YAML) && !family.equals(JSON)
                && !family.equals(CSS) && !family.equals(XML) && !family.equals(GENERIC)) {
            return true;
        }
        return false;
    }

    private static boolean hasBlockComment(String family) {
        switch (family) {
            case JAVA: case KOTLIN: case JS: case C: case GO: case RUST: case CSS:
            case SQL: case JSON:
                return true;
            default:
                return false;
        }
    }

    private static boolean hasBacktickString(String family) {
        switch (family) {
            case JS: case SHELL: case GO: case RUST:
                return true;
            default:
                return false;
        }
    }

    /** 行注释标记；{@code #} 类语言要额外判定边界 */
    private static String lineCommentMark(String family) {
        switch (family) {
            case JAVA: case KOTLIN: case JS: case C: case GO: case RUST: case CSS:
                return "//";
            case PYTHON: case SHELL: case YAML:
                return "#";
            case SQL:
                return "--";
            default:
                return null;
        }
    }

    /**
     * 行注释标记是否真的开注释：
     * {@code //} 与 {@code --} 任意位置都算；{@code #} 只在行首或前面是空白时才算
     * （避免把 {@code ${#var}}、URL 里的 {@code #} 误判成注释）。
     */
    private static boolean commentBoundaryOk(String code, int i, String mark) {
        if (!mark.equals("#")) return true;
        if (i == 0) return true;
        char prev = code.charAt(i - 1);
        return prev == '\n' || Character.isWhitespace(prev);
    }

    private static int scanQuoted(String code, int i, char quote) {
        int n = code.length();
        int j = i + 1;
        while (j < n) {
            char c = code.charAt(j);
            if (c == '\\') {
                j += 2;
                continue;
            }
            if (c == quote) return j + 1;
            // 单行字符串不跨行：未闭合时只染到行尾
            if (c == '\n' && quote != '`') return j;
            j++;
        }
        return n;
    }

    private static int scanNumber(String code, int i) {
        int n = code.length();
        int j = i;
        if (code.charAt(i) == '0' && i + 1 < n) {
            char p = code.charAt(i + 1);
            if (p == 'x' || p == 'X' || p == 'b' || p == 'B' || p == 'o') {
                j = i + 2;
                while (j < n && (Character.isLetterOrDigit(code.charAt(j)))) j++;
                return j;
            }
        }
        while (j < n && isDigit(code.charAt(j))) j++;
        if (j < n && code.charAt(j) == '.' && j + 1 < n && isDigit(code.charAt(j + 1))) {
            j++;
            while (j < n && isDigit(code.charAt(j))) j++;
        }
        if (j < n && (code.charAt(j) == 'e' || code.charAt(j) == 'E')) {
            int k = j + 1;
            if (k < n && (code.charAt(k) == '+' || code.charAt(k) == '-')) k++;
            if (k < n && isDigit(code.charAt(k))) {
                j = k;
                while (j < n && isDigit(code.charAt(j))) j++;
            }
        }
        // Java 的 1L / 1.5f / 2d 后缀
        if (j < n && "LlFfDd".indexOf(code.charAt(j)) >= 0) j++;
        return j;
    }

    private static boolean startsWith(String s, int at, String prefix) {
        return s.startsWith(prefix, at);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static Set<String> set(String... words) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(words)));
    }
}
