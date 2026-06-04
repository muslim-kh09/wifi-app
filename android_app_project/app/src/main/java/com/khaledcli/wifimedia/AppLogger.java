package com.khaledcli.wifimedia;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe in-memory log buffer with optional file persistence.
 *
 * Usage:
 *   AppLogger.init(context);             // once, in MainActivity.onCreate()
 *   AppLogger.info("TAG", "message");
 *   AppLogger.error("TAG", "msg", ex);
 *   List<String> lines = AppLogger.getLogs();
 *
 * Format: [YYYY-MM-DD HH:mm:ss] [LEVEL] [TAG] Message
 *
 * Safety guarantees:
 *  - Every public method is wrapped in try-catch; the logger NEVER crashes the app.
 *  - In-memory buffer is bounded to MAX_ENTRIES (500) — oldest entries evicted first.
 *  - File is capped at ~200 KB; it is trimmed automatically when exceeded.
 *  - All mutations are protected by a ReentrantLock (safe for concurrent threads).
 */
public class AppLogger {

    // ─────────────────────────────────────────────────────────────────────────
    // Constants
    // ─────────────────────────────────────────────────────────────────────────

    private static final String LOG_FILE_NAME  = "app_debug_logs.txt";
    private static final int    MAX_ENTRIES    = 500;
    private static final long   MAX_FILE_BYTES = 200_000L; // 200 KB

    // ─────────────────────────────────────────────────────────────────────────
    // State
    // ─────────────────────────────────────────────────────────────────────────

    private static final ReentrantLock         lock      = new ReentrantLock();
    private static final LinkedList<String>    buffer    = new LinkedList<>();
    private static final SimpleDateFormat      sdf       =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private static volatile File    logFile           = null;
    private static volatile boolean fileLoggingReady  = false;

    // ─────────────────────────────────────────────────────────────────────────
    // Initialisation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Initialise file logging. Call once from {@link MainActivity#onCreate}.
     * Safe to call multiple times; subsequent calls are no-ops.
     */
    public static void init(Context context) {
        if (fileLoggingReady) return;
        try {
            logFile = new File(context.getApplicationContext().getFilesDir(), LOG_FILE_NAME);
            fileLoggingReady = true;
            info("AppLogger", "Logger initialised — file: " + logFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e("AppLogger", "init() failed", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public logging API
    // ─────────────────────────────────────────────────────────────────────────

    public static void info(String tag, String message) {
        append("INFO", tag, message, null);
    }

    public static void warn(String tag, String message) {
        append("WARN", tag, message, null);
    }

    public static void error(String tag, String message) {
        append("ERROR", tag, message, null);
    }

    public static void error(String tag, String message, Throwable tr) {
        append("ERROR", tag, message, tr);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Read / utility API
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns a snapshot copy of the current log buffer (thread-safe). */
    public static List<String> getLogs() {
        lock.lock();
        try {
            return new ArrayList<>(buffer);
        } finally {
            lock.unlock();
        }
    }

    /** Returns all log lines joined with newlines — used for clipboard copy. */
    public static String getAllLogsAsString() {
        lock.lock();
        try {
            StringBuilder sb = new StringBuilder();
            for (String line : buffer) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } finally {
            lock.unlock();
        }
    }

    /** Current number of log entries in the buffer. */
    public static int getCount() {
        lock.lock();
        try {
            return buffer.size();
        } finally {
            lock.unlock();
        }
    }

    /** Clears the in-memory buffer and truncates the log file. */
    public static void clear() {
        lock.lock();
        try {
            buffer.clear();
            if (fileLoggingReady && logFile != null && logFile.exists()) {
                try {
                    new FileWriter(logFile, false).close(); // truncate to 0 bytes
                } catch (Exception e) {
                    Log.e("AppLogger", "clear(): file truncation failed", e);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal
    // ─────────────────────────────────────────────────────────────────────────

    private static void append(String level, String tag, String message, Throwable tr) {
        try {
            // Build the formatted line
            String timestamp;
            synchronized (sdf) { // SimpleDateFormat is not thread-safe
                timestamp = sdf.format(new Date());
            }
            String line = "[" + timestamp + "] [" + level + "] [" + tag + "] " + message;

            // Append stack trace inline if provided
            if (tr != null) {
                StringWriter sw = new StringWriter(256);
                tr.printStackTrace(new PrintWriter(sw));
                String trace = sw.toString().trim().replace("\n", "\n    ");
                line = line + "\n    " + trace;
            }

            // Mirror to system Logcat (never throws)
            try {
                if ("ERROR".equals(level)) Log.e(tag, message, tr);
                else if ("WARN".equals(level)) Log.w(tag, message);
                else Log.i(tag, message);
            } catch (Exception ignored) {}

            // Commit to in-memory buffer
            final String finalLine = line;
            lock.lock();
            try {
                buffer.addLast(finalLine);
                while (buffer.size() > MAX_ENTRIES) {
                    buffer.removeFirst(); // evict oldest
                }
                // Persist to file (best-effort)
                if (fileLoggingReady && logFile != null) {
                    writeToFile(finalLine);
                }
            } finally {
                lock.unlock();
            }
        } catch (Exception e) {
            // The logger itself must NEVER crash the application
            Log.e("AppLogger", "Internal logger error", e);
        }
    }

    /** Appends one line to the log file. Trims the file if it grows too large. */
    private static void writeToFile(String line) {
        try {
            if (logFile.exists() && logFile.length() > MAX_FILE_BYTES) {
                trimLogFile();
            }
            FileWriter fw = new FileWriter(logFile, true /* append */);
            fw.write(line);
            fw.write('\n');
            fw.close();
        } catch (Exception e) {
            Log.e("AppLogger", "writeToFile() error", e);
        }
    }

    /**
     * Reads the log file, keeps the last 500 lines, and rewrites it.
     * Called automatically when the file exceeds {@link #MAX_FILE_BYTES}.
     */
    private static void trimLogFile() {
        try {
            if (logFile == null || !logFile.exists()) return;
            LinkedList<String> lines = new LinkedList<>();
            BufferedReader br = new BufferedReader(new FileReader(logFile));
            String l;
            while ((l = br.readLine()) != null) {
                lines.addLast(l);
                if (lines.size() > 500) lines.removeFirst();
            }
            br.close();

            FileWriter fw = new FileWriter(logFile, false /* overwrite */);
            for (String kept : lines) {
                fw.write(kept);
                fw.write('\n');
            }
            fw.close();
        } catch (Exception e) {
            Log.e("AppLogger", "trimLogFile() error", e);
        }
    }
}
