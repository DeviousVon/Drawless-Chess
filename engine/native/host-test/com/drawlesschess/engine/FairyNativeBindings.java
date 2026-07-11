package com.drawlesschess.engine;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Linux/JVM acceptance harness for the exact JNI ABI used by Android. */
public final class FairyNativeBindings {
    private static native long nativeCreate(String variantConfigPath);
    private static native void nativeStart(long handle);
    private static native int nativeWrite(long handle, byte[] bytes, int offset, int length);
    private static native int nativeRead(long handle, byte[] bytes, int offset, int length);
    private static native int nativeReadError(long handle, byte[] bytes, int offset, int length);
    private static native void nativeClose(long handle);

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private FairyNativeBindings() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: FairyNativeBindings LIBRARY VARIANTS");
        }
        System.load(args[0]);

        verifySession(args[1], true);
        verifySession(args[1], false);
        System.out.println("PASSED host JNI lifecycle, identity, rules, search, and restart gates");
    }

    private static void verifySession(String variants, boolean exerciseSearch) throws Exception {
        long handle = nativeCreate(variants);
        if (handle <= 0) throw new AssertionError("nativeCreate returned an invalid handle");

        if (exerciseSearch) {
            expectThrows(IllegalStateException.class, () -> nativeCreate(variants));
            expectThrows(
                IndexOutOfBoundsException.class,
                () -> nativeWrite(handle, new byte[1], 1, 1)
            );
        }

        nativeStart(handle);
        try (OutputReader output = new OutputReader(handle, false);
             OutputReader errors = new OutputReader(handle, true)) {
            output.start();
            errors.start();

            send(handle, "uci\n");
            output.await("uciok");
            output.requireLine(
                "option name Drawless Patch Version type spin default 1 min 1 max 1"
            );
            output.requireSubstring("option name UCI_Variant", "var drawless");
            output.requireSubstring("option name UCI_Variant", "var escape");

            send(handle, "isready\n");
            output.await("readyok");

            if (exerciseSearch) {
                send(handle, "setoption name UCI_Variant value drawless\n");
                send(handle, "setoption name Threads value 1\n");
                send(handle, "setoption name Hash value 1\n");
                send(handle, "isready\n");
                output.await("readyok");
                send(
                    handle,
                    "position fen 6k1/7p/5Q2/8/8/8/8/6K1 w - - 0 1 " +
                        "moves f6f7 g8h8 f7f6 h8g8 f6f7 g8h8 f7f6\n"
                );
                send(handle, "go depth 4\n");
                output.awaitPrefix("bestmove h8g8");
                output.requireSubstring("info depth", "score mate 1");
            }

            nativeClose(handle);
            output.awaitEnd();
            errors.awaitEnd();
            if (!errors.lines().isEmpty()) {
                throw new AssertionError("Unexpected native stderr: " + errors.lines());
            }
        } finally {
            nativeClose(handle);
        }
    }

    private static void send(long handle, String command) {
        byte[] bytes = command.getBytes(StandardCharsets.UTF_8);
        int count = nativeWrite(handle, bytes, 0, bytes.length);
        if (count != bytes.length) {
            throw new AssertionError("Short JNI write: " + count + " of " + bytes.length);
        }
    }

    private static <T extends Throwable> void expectThrows(
        Class<T> expected,
        ThrowingRunnable action
    ) throws Exception {
        try {
            action.run();
        } catch (Throwable error) {
            if (expected.isInstance(error)) return;
            throw new AssertionError("Expected " + expected.getName() + ", received " + error, error);
        }
        throw new AssertionError("Expected " + expected.getName());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class OutputReader implements AutoCloseable {
        private static final String END = "\u0000<END>";

        private final long handle;
        private final boolean standardError;
        private final BlockingQueue<String> pending = new LinkedBlockingQueue<>();
        private final List<String> all = Collections.synchronizedList(new ArrayList<>());
        private final Thread thread;
        private volatile Throwable failure;

        OutputReader(long handle, boolean standardError) {
            this.handle = handle;
            this.standardError = standardError;
            this.thread = new Thread(this::readLoop, standardError ? "host-jni-stderr" : "host-jni-stdout");
            this.thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        List<String> lines() {
            synchronized (all) {
                return List.copyOf(all);
            }
        }

        void await(String exactLine) throws Exception {
            awaitMatching(line -> line.equals(exactLine), exactLine);
        }

        void awaitPrefix(String prefix) throws Exception {
            awaitMatching(line -> line.startsWith(prefix), prefix);
        }

        void requireLine(String line) {
            if (!lines().contains(line)) {
                throw new AssertionError("Missing line '" + line + "' in " + lines());
            }
        }

        void requireSubstring(String linePrefix, String requiredText) {
            boolean found = lines().stream().anyMatch(
                line -> line.startsWith(linePrefix) && line.contains(requiredText)
            );
            if (!found) {
                throw new AssertionError(
                    "Missing '" + requiredText + "' on '" + linePrefix + "' line in " + lines()
                );
            }
        }

        void awaitEnd() throws Exception {
            thread.join(TIMEOUT.toMillis());
            if (thread.isAlive()) throw new AssertionError("Native output reader did not reach EOF");
            if (failure != null) throw new AssertionError("Native output reader failed", failure);
        }

        @Override
        public void close() throws Exception {
            awaitEnd();
        }

        private void awaitMatching(LinePredicate predicate, String description) throws Exception {
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new AssertionError("Timed out waiting for " + description + "; output=" + lines());
                }
                String line = pending.poll(remaining, TimeUnit.NANOSECONDS);
                if (line == null) {
                    throw new AssertionError("Timed out waiting for " + description + "; output=" + lines());
                }
                if (line.equals(END)) {
                    throw new AssertionError("Native output ended before " + description + "; output=" + lines());
                }
                if (predicate.matches(line)) return;
            }
        }

        private void readLoop() {
            byte[] buffer = new byte[16 * 1024];
            StringBuilder partial = new StringBuilder();
            try {
                while (true) {
                    int count = standardError
                        ? nativeReadError(handle, buffer, 0, buffer.length)
                        : nativeRead(handle, buffer, 0, buffer.length);
                    if (count < 0) break;
                    if (count == 0 || count > buffer.length) {
                        throw new IllegalStateException("Invalid native read count " + count);
                    }
                    partial.append(new String(buffer, 0, count, StandardCharsets.UTF_8));
                    int newline;
                    while ((newline = partial.indexOf("\n")) >= 0) {
                        String line = partial.substring(0, newline);
                        if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                        partial.delete(0, newline + 1);
                        all.add(line);
                        pending.add(line);
                    }
                }
                if (!partial.isEmpty()) {
                    String line = partial.toString();
                    all.add(line);
                    pending.add(line);
                }
            } catch (Throwable error) {
                failure = error;
            } finally {
                pending.add(END);
            }
        }
    }

    @FunctionalInterface
    private interface LinePredicate {
        boolean matches(String line);
    }
}
