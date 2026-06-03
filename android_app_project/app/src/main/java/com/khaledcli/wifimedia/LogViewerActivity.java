package com.khaledcli.wifimedia;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Clean UI to display background daemon logs natively.
 */
public class LogViewerActivity extends Activity {

    private TextView tvLogs;
    private Handler handler;
    private Runnable logUpdater;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_viewer);

        tvLogs = findViewById(R.id.tv_logs);
        Button btnCopy = findViewById(R.id.btn_copy_logs);
        Button btnClear = findViewById(R.id.btn_clear_logs);

        btnCopy.setOnClickListener(v -> copyLogsToClipboard());
        btnClear.setOnClickListener(v -> clearLogs());

        handler = new Handler(Looper.getMainLooper());
        logUpdater = new Runnable() {
            @Override
            public void run() {
                refreshLogs();
                // Auto-refresh every 2 seconds while screen is open
                handler.postDelayed(this, 2000);
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(logUpdater);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(logUpdater);
    }

    private void refreshLogs() {
        List<String> logs = AppLogger.getLogs();
        if (logs.isEmpty()) {
            tvLogs.setText("No logs available.");
        } else {
            StringBuilder sb = new StringBuilder();
            for (String log : logs) {
                sb.append(log).append("\n");
            }
            tvLogs.setText(sb.toString());
        }
    }

    private void copyLogsToClipboard() {
        String allLogs = AppLogger.getAllLogsAsString();
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && !allLogs.isEmpty()) {
            ClipData clip = ClipData.newPlainText("App Debug Logs", allLogs);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "Logs copied to clipboard (" + AppLogger.getCount() + " lines)", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "No logs to copy", Toast.LENGTH_SHORT).show();
        }
    }

    private void clearLogs() {
        AppLogger.clear();
        refreshLogs();
        Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show();
    }
}
