/*
 * Copyright (c) 2002-2026, the original author or authors.
 *
 * This software is distributable under the BSD license. See the terms of the
 * BSD license in the documentation provided with this software.
 *
 * https://opensource.org/licenses/BSD-3-Clause
 */
package org.jline.builtins;

import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regex utilities that guard against catastrophic backtracking (ReDoS).
 *
 * <p>Java's {@code java.util.regex} engine uses backtracking, so patterns with
 * nested quantifiers (e.g. {@code (a+)+b}) can take exponential time on
 * non-matching input. This class wraps input in a {@link CharSequence} that
 * enforces a wall-clock deadline, throwing {@link RegexTimeoutException} if
 * matching takes too long.</p>
 *
 * <p>Callers obtain a {@link Matcher} through {@link #matcher} and catch
 * {@link RegexTimeoutException} themselves.</p>
 */
final class SafeRegex {

    /** Default timeout for regex matching operations. */
    static final long DEFAULT_TIMEOUT_MS = 1500;

    /**
     * How often (in {@code charAt} calls) the deadline is checked.
     * Checking every call would add measurable overhead; checking every
     * 1024 calls keeps the cost negligible while still catching runaway
     * backtracking within milliseconds on typical input.
     */
    private static final int CHECK_INTERVAL = 1024;

    private SafeRegex() {}

    /**
     * Create a {@link Matcher} that will throw {@link RegexTimeoutException}
     * if matching exceeds the default timeout.
     */
    static Matcher matcher(Pattern pattern, CharSequence input) {
        return matcher(pattern, input, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Create a {@link Matcher} that will throw {@link RegexTimeoutException}
     * if matching exceeds the given timeout.  The timeout starts lazily on
     * the first deadline check during matching (not when the {@link Matcher}
     * is created), so it measures actual matching time.
     */
    static Matcher matcher(Pattern pattern, CharSequence input, long timeoutMs) {
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        return pattern.matcher(new TimeoutCharSequence(input, timeoutNanos));
    }

    /**
     * A {@link CharSequence} wrapper that enforces a wall-clock deadline.
     *
     * <p>The Java regex engine calls {@link #charAt(int)} for every position
     * it considers during matching.  During catastrophic backtracking the
     * call count explodes; we check {@link System#nanoTime()} every
     * {@value #CHECK_INTERVAL} calls and throw if the deadline has passed.</p>
     *
     * <p>The deadline is lazily initialised on the first {@code charAt}
     * check so the timeout measures actual matching time, not the gap
     * between {@link Matcher} creation and first use.  Instances created
     * via {@link #subSequence} share the same deadline array, so the
     * timeout covers the entire matching operation.</p>
     */
    private static final class TimeoutCharSequence implements CharSequence {

        private final CharSequence inner;
        private final long timeoutNanos;
        /** Shared across {@link #subSequence} calls; element 0 holds the deadline (0 = not yet started). */
        private final long[] sharedDeadline;

        private int calls;

        TimeoutCharSequence(CharSequence inner, long timeoutNanos) {
            this.inner = inner;
            this.timeoutNanos = timeoutNanos;
            this.sharedDeadline = new long[1];
        }

        private TimeoutCharSequence(CharSequence inner, long timeoutNanos, long[] sharedDeadline) {
            this.inner = inner;
            this.timeoutNanos = timeoutNanos;
            this.sharedDeadline = sharedDeadline;
        }

        @Override
        public char charAt(int index) {
            if (++calls % CHECK_INTERVAL == 0) {
                long now = System.nanoTime();
                if (sharedDeadline[0] == 0) {
                    sharedDeadline[0] = now + timeoutNanos;
                } else if (now > sharedDeadline[0]) {
                    throw new RegexTimeoutException();
                }
            }
            return inner.charAt(index);
        }

        @Override
        public int length() {
            return inner.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new TimeoutCharSequence(inner.subSequence(start, end), timeoutNanos, sharedDeadline);
        }

        @Override
        public String toString() {
            return inner.toString();
        }
    }
}
