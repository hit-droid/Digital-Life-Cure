package com.digitallife.ui.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * v1.188.0 {@link CodeHighlighter} 的回归：只锁「切词结果」，不涉及颜色。
 *
 * <p>高亮最容易翻车的地方是 token 边界——字符串里的 {@code //}、转义引号、
 * SQL 的大小写、Python 三引号、{@code ${#var}} 里的井号。这里逐条钉住。</p>
 */
public class CodeHighlighterTest {

    private static CodeHighlighter.Kind kindOf(String code, String info, String needle) {
        int at = code.indexOf(needle);
        assertTrue("测试用例写错了：" + needle + " 不在代码里", at >= 0);
        for (CodeHighlighter.Token t : CodeHighlighter.highlight(code, info)) {
            if (t.start <= at && at < t.end) return t.kind;
        }
        return null;
    }

    private static String tokenText(String code, CodeHighlighter.Token t) {
        return code.substring(t.start, t.end);
    }

    // ==================== 语言识别 ====================

    @Test
    public void familyOf_knownAliases() {
        assertEquals(CodeHighlighter.JAVA, CodeHighlighter.familyOf("java"));
        assertEquals(CodeHighlighter.PYTHON, CodeHighlighter.familyOf("py"));
        assertEquals(CodeHighlighter.SHELL, CodeHighlighter.familyOf("bash"));
        assertEquals(CodeHighlighter.JS, CodeHighlighter.familyOf("typescript"));
        assertEquals(CodeHighlighter.C, CodeHighlighter.familyOf("c++"));
        assertEquals(CodeHighlighter.XML, CodeHighlighter.familyOf("html"));
    }

    @Test
    public void familyOf_ignoresFenceOptions() {
        assertEquals(CodeHighlighter.JAVA, CodeHighlighter.familyOf("language-java"));
        assertEquals(CodeHighlighter.PYTHON, CodeHighlighter.familyOf("python {linenos=true}"));
        assertEquals(CodeHighlighter.SQL, CodeHighlighter.familyOf("  SQL  "));
        assertEquals(CodeHighlighter.GENERIC, CodeHighlighter.familyOf(""));
        assertEquals(CodeHighlighter.GENERIC, CodeHighlighter.familyOf(null));
    }

    @Test
    public void familyOf_unknownLanguageStaysGeneric() {
        assertEquals(CodeHighlighter.GENERIC, CodeHighlighter.familyOf("brainfuck"));
    }

    // ==================== Java ====================

    @Test
    public void java_keywordTypeStringNumberComment() {
        String code = "public class Demo {\n"
                + "  // 说明\n"
                + "  String s = \"hi\";\n"
                + "  int n = 42;\n"
                + "}\n";
        assertEquals(CodeHighlighter.Kind.KEYWORD, kindOf(code, "java", "public"));
        assertEquals(CodeHighlighter.Kind.TYPE, kindOf(code, "java", "String"));
        assertEquals(CodeHighlighter.Kind.STRING, kindOf(code, "java", "hi"));
        assertEquals(CodeHighlighter.Kind.NUMBER, kindOf(code, "java", "42"));
        assertEquals(CodeHighlighter.Kind.COMMENT, kindOf(code, "java", "说明"));
    }

    @Test
    public void java_keywordNeedsWordBoundary() {
        // classy 不是 class，不能被染成关键字
        String code = "classy name = null;";
        assertNull(kindOf(code, "java", "classy"));
        assertNotNull(kindOf(code, "java", "null"));
    }

    @Test
    public void java_blockCommentSpansLines() {
        String code = "/* 第一行\n第二行 */ int a = 1;";
        List<CodeHighlighter.Token> tokens = CodeHighlighter.highlight(code, "java");
        CodeHighlighter.Token first = tokens.get(0);
        assertEquals(CodeHighlighter.Kind.COMMENT, first.kind);
        assertEquals("/* 第一行\n第二行 */", tokenText(code, first));
    }

    @Test
    public void java_escapedQuoteDoesNotEndString() {
        String code = "String s = \"a\\\"b\"; int n = 1;";
        CodeHighlighter.Token t = null;
        for (CodeHighlighter.Token candidate : CodeHighlighter.highlight(code, "java")) {
            if (candidate.start == code.indexOf('"')) t = candidate;
        }
        assertNotNull(t);
        assertEquals("\"a\\\"b\"", tokenText(code, t));
    }

    @Test
    public void java_slashSlashInsideStringIsNotComment() {
        String code = "String u = \"https://example.com\";";
        // URL 里的 // 属于字符串，不能把后半行整段染成注释
        assertEquals(CodeHighlighter.Kind.STRING, kindOf(code, "java", "example"));
    }

    // ==================== Python / Shell / SQL ====================

    @Test
    public void python_tripleQuoteIsOneString() {
        String code = "def f():\n    \"\"\"文档\n    说明\"\"\"\n    return 1\n";
        CodeHighlighter.Token triple = null;
        for (CodeHighlighter.Token t : CodeHighlighter.highlight(code, "python")) {
            if (tokenText(code, t).startsWith("\"\"\"")) triple = t;
        }
        assertNotNull(triple);
        assertEquals(CodeHighlighter.Kind.STRING, triple.kind);
        assertTrue(tokenText(code, triple).contains("说明"));
    }

    @Test
    public void python_hashCommentNeedsBoundary() {
        // ${#var} 这类井号不是注释；行尾注释才是
        String shell = "echo ${#var}\n";
        assertNull(kindOf(shell, "shell", "#var"));
        String py = "x = 1  # 说明\n";
        assertEquals(CodeHighlighter.Kind.COMMENT, kindOf(py, "python", "说明"));
    }

    @Test
    public void shell_shebangIsCommentAndBacktickIsString() {
        String code = "#!/bin/bash\necho \"hi\" `date`\n";
        assertEquals(CodeHighlighter.Kind.COMMENT, kindOf(code, "shell", "/bin/bash"));
        assertEquals(CodeHighlighter.Kind.STRING, kindOf(code, "shell", "hi"));
        assertEquals(CodeHighlighter.Kind.STRING, kindOf(code, "shell", "date"));
    }

    @Test
    public void sql_keywordsAreCaseInsensitive() {
        String code = "select id from users where age > 18;";
        assertEquals(CodeHighlighter.Kind.KEYWORD, kindOf(code, "sql", "select"));
        assertEquals(CodeHighlighter.Kind.KEYWORD, kindOf(code, "sql", "from"));
        assertEquals(CodeHighlighter.Kind.KEYWORD, kindOf(code, "sql", "where"));
        assertEquals(CodeHighlighter.Kind.NUMBER, kindOf(code, "sql", "18"));
    }

    // ==================== JSON / XML / 猜测 ====================

    @Test
    public void json_literalsAndStrings() {
        String code = "{\"ok\": true, \"n\": null}";
        assertEquals(CodeHighlighter.Kind.STRING, kindOf(code, "json", "ok"));
        assertEquals(CodeHighlighter.Kind.KEYWORD, kindOf(code, "json", "true"));
        assertEquals(CodeHighlighter.Kind.KEYWORD, kindOf(code, "json", "null"));
    }

    @Test
    public void xml_commentIsRecognized() {
        String code = "<!-- 注释 -->\n<root a=\"1\" />";
        assertEquals(CodeHighlighter.Kind.COMMENT, kindOf(code, "xml", "注释"));
        assertEquals(CodeHighlighter.Kind.STRING, kindOf(code, "xml", "1"));
    }

    @Test
    public void guess_javaWhenFenceHasNoLanguage() {
        String code = "public class Foo {\n  int a = 1;\n}";
        assertEquals(CodeHighlighter.Kind.KEYWORD, kindOf(code, "", "public"));
    }

    @Test
    public void guess_pythonWhenFenceHasNoLanguage() {
        String code = "def main():\n    print(\"hi\")\n";
        assertEquals(CodeHighlighter.Kind.KEYWORD, kindOf(code, null, "def"));
    }

    @Test
    public void generic_proseProducesNoTokens() {
        List<CodeHighlighter.Token> tokens =
                CodeHighlighter.highlight("hello world, 这是纯文本输出", "text");
        assertEquals(0, tokens.size());
    }

    // ==================== 结构约束 ====================

    @Test
    public void tokens_areSortedAndNonOverlapping() {
        String code = "public static void main(String[] args) {\n"
                + "  // 注释\n"
                + "  System.out.println(\"hi\" + 1);\n"
                + "}";
        List<CodeHighlighter.Token> tokens = CodeHighlighter.highlight(code, "java");
        assertTrue(tokens.size() > 3);
        int prevEnd = -1;
        for (CodeHighlighter.Token t : tokens) {
            assertTrue("token 起点必须 >= 前一个 token 的终点", t.start >= prevEnd);
            assertTrue("token 区间非空", t.end > t.start);
            assertTrue("token 不能越界", t.end <= code.length());
            prevEnd = t.end;
        }
    }

    @Test
    public void highlight_nullOrEmptyIsSafe() {
        assertEquals(0, CodeHighlighter.highlight(null, "java").size());
        assertEquals(0, CodeHighlighter.highlight("", "java").size());
    }

    @Test
    public void number_handlesHexAndFloatAndSuffix() {
        String code = "int a = 0xFF; double d = 3.14e-2; long l = 10L;";
        assertEquals(CodeHighlighter.Kind.NUMBER, kindOf(code, "java", "0xFF"));
        assertEquals(CodeHighlighter.Kind.NUMBER, kindOf(code, "java", "3.14"));
        assertEquals(CodeHighlighter.Kind.NUMBER, kindOf(code, "java", "10L"));
    }
}
