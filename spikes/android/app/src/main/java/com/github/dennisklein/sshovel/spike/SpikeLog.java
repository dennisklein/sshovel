// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.spike;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Log to logcat (tag sshovel-spike) and to the in-app log view. */
final class SpikeLog {
    static final String TAG = "sshovel-spike";
    private static final List<String> lines = new ArrayList<>();
    private static Consumer<String> listener;

    private SpikeLog() {}

    static synchronized void i(String msg) {
        Log.i(TAG, msg);
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(new Date()) + " " + msg;
        lines.add(line);
        if (lines.size() > 400) lines.remove(0);
        if (listener != null) listener.accept(line);
    }

    static void e(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        i(msg + ": " + t);
    }

    static synchronized void setListener(Consumer<String> l) {
        listener = l;
        if (l != null) for (String s : lines) l.accept(s);
    }
}
