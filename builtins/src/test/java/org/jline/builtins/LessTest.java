/*
 * Copyright (c) 2002-2026, the original author or authors.
 *
 * This software is distributable under the BSD license. See the terms of the
 * BSD license in the documentation provided with this software.
 *
 * https://opensource.org/licenses/BSD-3-Clause
 */
package org.jline.builtins;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.jline.terminal.Attributes;
import org.jline.terminal.Attributes.InputFlag;
import org.jline.terminal.Size;
import org.jline.terminal.impl.LineDisciplineTerminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LessTest {

    private static final String EVIL_PATTERN = SafeRegexTest.EVIL_PATTERN;

    private static String evilLine() {
        return SafeRegexTest.evilInput();
    }

    private static LineDisciplineTerminal terminal(ByteArrayOutputStream output) throws IOException {
        LineDisciplineTerminal terminal = new LineDisciplineTerminal("less", "xterm", output, StandardCharsets.UTF_8);
        terminal.setSize(new Size(80, 10));
        // Deliver '\r' as is, so that it validates the search pattern
        Attributes attrs = terminal.getAttributes();
        attrs.setInputFlag(InputFlag.ICRNL, false);
        terminal.setAttributes(attrs);
        return terminal;
    }

    private static void type(LineDisciplineTerminal terminal, String keys) throws IOException {
        terminal.processInputBytes(keys.getBytes(StandardCharsets.UTF_8));
    }

    private static List<Source> sources(String... lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        List<Source> sources = new ArrayList<>();
        sources.add(new Source.InputStreamSource(
                new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8)), true, "test"));
        return sources;
    }

    /**
     * Less that records the status message shown by each redraw.
     */
    static class RecordingLess extends Less {
        final List<String> messages = new ArrayList<>();

        RecordingLess(LineDisciplineTerminal terminal) {
            super(terminal, Paths.get("."), Options.compile(Less.usage()).parse(new String[] {"--ignorercfiles"}));
        }

        @Override
        boolean display(boolean oneScreen) throws IOException {
            boolean res = super.display(oneScreen);
            messages.add(message);
            return res;
        }
    }

    @Test(timeout = 20000)
    public void testForwardSearchReDoS() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LineDisciplineTerminal terminal = terminal(output);
        String evil = evilLine();
        type(terminal, "/" + EVIL_PATTERN + "\r");
        type(terminal, "q");

        RecordingLess less = new RecordingLess(terminal);
        long start = System.nanoTime();
        less.run(sources("first", evil, evil, evil, evil, evil, evil, evil, "last"));
        long elapsedMs = (System.nanoTime() - start) / 1000000;

        assertTrue("Search timeout should be reported, got " + less.messages,
                less.messages.contains("Pattern search timed out"));
        assertEquals(0, less.firstLineToDisplay);
        assertTrue("less took " + elapsedMs + " ms", elapsedMs < 15000);
    }

    @Test(timeout = 20000)
    public void testBackwardSearchReDoS() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LineDisciplineTerminal terminal = terminal(output);
        String evil = evilLine();
        // Jump to the last line, then search backward
        type(terminal, "G");
        type(terminal, "?" + EVIL_PATTERN + "\r");
        type(terminal, "q");

        RecordingLess less = new RecordingLess(terminal);
        long start = System.nanoTime();
        less.run(sources(evil, evil, evil, evil, evil, evil, evil, evil, evil, evil, evil, evil, "last"));
        long elapsedMs = (System.nanoTime() - start) / 1000000;

        assertTrue("Search timeout should be reported, got " + less.messages,
                less.messages.contains("Pattern search timed out"));
        assertTrue("less took " + elapsedMs + " ms", elapsedMs < 15000);
    }

    @Test(timeout = 20000)
    public void testHighlightReDoS() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LineDisciplineTerminal terminal = terminal(output);
        String evil = evilLine();
        // The pattern matches "last" so the search succeeds, but highlighting
        // the lines on screen would backtrack catastrophically
        type(terminal, "/" + EVIL_PATTERN + "|last\r");
        type(terminal, "k");
        type(terminal, "q");

        RecordingLess less = new RecordingLess(terminal);
        long start = System.nanoTime();
        less.run(sources("first", "last", evil, evil, evil, evil, evil, evil, evil, evil));
        long elapsedMs = (System.nanoTime() - start) / 1000000;

        assertTrue("Highlight timeout should be reported, got " + less.messages,
                less.messages.contains("Pattern highlighting timed out"));
        assertTrue("less took " + elapsedMs + " ms", elapsedMs < 15000);
    }

    @Test(timeout = 20000)
    public void testDisplayPatternReDoS() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LineDisciplineTerminal terminal = terminal(output);
        String evil = evilLine();
        // Filter the displayed lines ('&'): matching the filter against the
        // lines on screen would backtrack catastrophically
        type(terminal, "&" + EVIL_PATTERN + "\r");
        type(terminal, "q");

        RecordingLess less = new RecordingLess(terminal);
        long start = System.nanoTime();
        less.run(sources("first", evil, evil, evil, evil, evil, evil, evil, evil, "last"));
        long elapsedMs = (System.nanoTime() - start) / 1000000;

        assertTrue("Filter timeout should be reported, got " + less.messages,
                less.messages.contains("Pattern filtering timed out"));
        assertNull(less.displayPattern);
        assertTrue("less took " + elapsedMs + " ms", elapsedMs < 15000);
    }

    @Test(timeout = 10000)
    public void testSearch() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LineDisciplineTerminal terminal = terminal(output);
        type(terminal, "/ba[rz]\r");
        type(terminal, "q");

        RecordingLess less = new RecordingLess(terminal);
        less.run(sources("foo", "qux", "bar", "baz"));

        assertEquals(2, less.firstLineToDisplay);
        assertFalse(less.messages.contains("Pattern not found"));
        assertFalse(less.messages.contains("Pattern search timed out"));
        assertFalse(less.messages.contains("Pattern highlighting timed out"));
    }

    @Test(timeout = 10000)
    public void testSearchNotFound() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LineDisciplineTerminal terminal = terminal(output);
        type(terminal, "/nothere\r");
        type(terminal, "q");

        RecordingLess less = new RecordingLess(terminal);
        less.run(sources("foo", "bar", "baz"));

        assertEquals(0, less.firstLineToDisplay);
        assertTrue(less.messages.contains("Pattern not found"));
    }

    @Test(timeout = 10000)
    public void testSearchWithDisplayPattern() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LineDisciplineTerminal terminal = terminal(output);
        // Only display the lines containing "ba", then search for "z":
        // "zoo" is filtered out, so the search lands on "baz"
        type(terminal, "&ba\r");
        type(terminal, "/z\r");
        type(terminal, "q");

        RecordingLess less = new RecordingLess(terminal);
        less.run(sources("foo", "bar", "zoo", "baz"));

        assertEquals("ba", less.displayPattern);
        assertEquals(3, less.firstLineToDisplay);
        assertFalse(less.messages.contains("Pattern not found"));
        assertFalse(less.messages.contains("Pattern filtering timed out"));
    }

    @Test
    public void testStyleMatchesSameAsAttributedString() throws Exception {
        Method styleMatches = Less.class.getDeclaredMethod(
                "styleMatches", AttributedString.class, Pattern.class, AttributedStyle.class);
        styleMatches.setAccessible(true);
        AttributedStyle inverse = AttributedStyle.DEFAULT.inverse();
        String[] lines = {
                "foo bar baz",
                "\u001B[1mbold bar\u001B[0m and \u001B[32mgreen baz\u001B[0m",
                "no match here",
                "",
                "barbarbar"
        };
        Pattern[] patterns = {
                Pattern.compile("(ba[rz])"),
                Pattern.compile("(x*)"),
                Pattern.compile("(r b)")
        };
        for (String l : lines) {
            AttributedString line = AttributedString.fromAnsi(l);
            for (Pattern p : patterns) {
                AttributedString expected = line.styleMatches(p, inverse);
                AttributedString actual = (AttributedString) styleMatches.invoke(null, line, p, inverse);
                assertEquals(l + " / " + p, expected, actual);
                assertEquals(l + " / " + p, expected.toAnsi(), actual.toAnsi());
            }
        }
    }
}
