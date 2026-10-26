// Modified to match Python UI text and behavior
package com.bi3bju.ft8_plus;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.UnderlineSpan;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.ref.WeakReference;
import java.math.BigInteger;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "FT8Plus";
    private static final int SAMPLE_RATE = 12000;
    private static final int SLOT_SECONDS = 15;
    private static final int SAMPLES_PER_SLOT = SAMPLE_RATE * SLOT_SECONDS;
    private static final int MAX_DECODE_HISTORY = 100;
    // Slots whose average absolute amplitude is at or above this
    // threshold are handed to FT8Decoder; slots below it are skipped
    // to save CPU. Set to 5: silent slots (avgAmp 0.0) and very low
    // noise (avgAmp < 5) are skipped, while real FT8 signals
    // (typically well above 5) always reach the decoder.
    // With AGC disabled (UNPROCESSED / VOICE_RECOGNITION source) the
    // noise floor drops, so a lower gate still filters genuinely
    // silent slots while letting weak-signal slots through. Set to 2.
    private static final int AUDIO_VALID_THRESHOLD = 2;
    // A slot whose average absolute amplitude is below this threshold
    // is treated as "no audio input": the device is still returning
    // samples (totalSamplesWritten keeps growing) but every sample is
    // zero or near zero. Used by the slot-start health check in
    // slotSchedulingLoop to catch an emulator without a microphone or
    // a muted hardware switch.
    private static final double SILENT_AMP_THRESHOLD = 1.0;
    private static final int BUFFER_SECONDS = 60;
    private static final int BUFFER_CAPACITY = SAMPLE_RATE * BUFFER_SECONDS;

    private static final int PERMISSION_REQ_CODE = 200;
    private static final int LOCATION_PERMISSION_REQ_CODE = 201;

    // Transparent transmission parameters
    private static final int MAX_RAW_DATA_BYTES = 574;
    private static final int MAX_FRAMES = 64;
    private static final int DELAY_SECONDS = 60;
    private static final int FREQ_GROUP_TOLERANCE = 10;
    private static final String CONFIG_FILE_NAME = "config.txt";

    // Log file constants
    private static final String HISTORY_FILE_NAME = "history.txt";
    private static final String STATUS_FILE_NAME = "status.txt";
    private static final int MAX_LOG_LINES = 100;
    // TX action button symbols. Background colors are unchanged;
    // only the label switches between TX and Stop.
    private static final String SYMBOL_TX = "\u25B6";    // ▶
    private static final String SYMBOL_STOP = "\u25A0";  // ■
    // GUI status area line cap. Kept as a separate constant so
    // the widget limit can diverge from MAX_LOG_LINES later
    // without touching appendStatusMessage().
    private static final int MAX_STATUS_LINES = 100;
    // Trim in batches once the buffer exceeds MAX_STATUS_LINES;
    // amortizes the O(n) Tk/Text-style cost across many appends.
    private static final int STATUS_TRIM_BATCH = 50;

    // Free text character set
    private static final String FREE_TEXT_CHARSET = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ+-./?";

    // Contact limits
    private static final int MAX_CONTACTS = 100;
    private static final int MAX_NOTE_BYTES = 32;

    // ===== Beacon interval configuration =====
    private static final int BEACON_INTERVAL_SLOTS = 16;  // 16 slots = 240 seconds

    // Static time formats (UTC)
    private static final SimpleDateFormat utcFullFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private static final SimpleDateFormat utcSlotFormat = new SimpleDateFormat("yyMMdd_HHmmss", Locale.US);
    private static final SimpleDateFormat utcDateOnly = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    static {
        TimeZone utcZone = TimeZone.getTimeZone("UTC");
        utcFullFormat.setTimeZone(utcZone);
        utcSlotFormat.setTimeZone(utcZone);
        utcDateOnly.setTimeZone(utcZone);
    }

    // Ring buffer
    private final short[] ringBuffer = new short[BUFFER_CAPACITY];
    private int writePos = 0;
    private final AtomicLong totalSamplesWritten = new AtomicLong(0);

    // UI widgets
    private TextView tvUtcTime;
    private TextView tvStatus;
    private ScrollView scrollView;
    private TextView tvLedStatus;
    private Button btnTransmitStop;
    private EditText etTxMessage;
    private EditText etTxFreq;
    private ListView lvHistory;
    private Button btnCallsign;
    private Button btnBeacon;
    private Button btnContacts;
    private Button btnAbout;
    private CheckBox chkPreambleNoise;
    private TextView tvByteCount;
    private TextView tvFrameCount;
    private Button btnGrid;
    // Configuration parameters
    private String callsign = "";
    private String grid = "";
    private int txFreq = 1500;
    private boolean preambleNoise = false;

    // History adapter
    private HistoryAdapter historyAdapter;
    private final List<HistoryEntry> historyList = new ArrayList<>();
    private final Map<String, Integer> historyIndexMap = new HashMap<>();

    // Cache of first timestamps for TX sessions
    private final Map<String, Long> sendSessionFirstTimestamp = new HashMap<>();

    // Thread pool
    private final ExecutorService decodeExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r);
        t.setPriority(Thread.MAX_PRIORITY - 1);
        return t;
    });

    // Dedicated single-thread executor for status.txt writes.
    // File I/O runs here so the UI thread never blocks on disk,
    // and the single thread serialises writes to appendToFile()
    // / trimFileLines() so they are never called concurrently
    // on the same file.
    private final ExecutorService logWriter = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FT8StatusLog");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private final AtomicBoolean isTransmitting = new AtomicBoolean(false);
    private final AtomicBoolean receivePaused = new AtomicBoolean(false);
    private Thread recordingThread;
    private Thread slotSchedulerThread;
    private Thread transmissionThread;

    // Pending action to run after a location permission prompt.
    private Runnable pendingLocationPermissionAction = null;

    // Beacon-related state
    private final AtomicBoolean beaconStop = new AtomicBoolean(false);
    private Thread beaconThread;
    private long nextBeaconSlot = 0;
    private boolean beaconEnabled = false;

    private volatile AudioTrack currentAudioTrack = null;

    private final UtcDashboardTicker utcDashboardRunnable = new UtcDashboardTicker(this);

    // Receive buffer pool
    private final Map<String, TransparentBuffer> transparentBuffers = new HashMap<>();
    private int nextSessionId = 1;
    // ---------- NTP ----------
    private volatile double ntpOffset = 0.0;
    private volatile boolean ntpSyncAttempted = false;
    private final Object ntpLock = new Object();

    // ---------- Audio input latency compensation ----------
    // AudioRecord on many Android devices (especially MIUI builds)
    // delivers the microphone signal 1-2 seconds later than the wall
    // clock. The FT8 decoder reports this as a positive DT. We track
    // that DT across successfully decoded messages with an EMA and
    // shift the ring-buffer window by the EMA amount so the signal
    // occupies the whole 15-second window instead of its last ~12 s.
    // Aggressive calibration:
    //   - DT_EMA_ALPHA = 0.5 makes the estimate follow changes within
    //     two or three slots.
    //   - DT_JUMP_THRESHOLD_SECONDS forces a hard snap when a single
    //     decode differs from the current average by more than this
    //     amount; the EMA itself would take many slots to close such
    //     a gap and every intervening slot would decode with the
    //     wrong trigger offset.
    //   - DT_MIN_SECONDS lowered to 0.1: after convergence the
    //     residual DT oscillates around zero and a 0.2 floor would
    //     reject most of those readings, leaving the EMA stuck on a
    //     stale value.
    private static final double DT_EMA_ALPHA = 0.5;
    private static final double DT_JUMP_THRESHOLD_SECONDS = 1.0;
    private static final double DT_MIN_SECONDS = 0.1;
    private static final double DT_MAX_SECONDS = 4.0;
    private volatile double dtAvgSeconds = 0.0;
    private volatile boolean dtAvgInitialized = false;
    private final Object dtLock = new Object();

    // ---------- Contacts ----------
    private static class Contact {
        String callsign;
        String remark;
        Contact(String callsign, String remark) {
            this.callsign = callsign;
            this.remark = remark;
        }
    }
    private List<Contact> contactList = new ArrayList<>();
    private final String CONTACT_FILE = "contact.txt";

    // ---------- Inner classes ----------
    private static class Frame {
        int f2;
        byte[] data;
        Frame(int f2, byte[] data) { this.f2 = f2; this.data = data; }
    }

    // ---------- HistoryEntry ----------
    private static class HistoryEntry {
        String direction;
        long timestamp;
        int freq;
        int snr;
        float dt;
        int framesReceived;
        int totalFrames;
        String text;
        String status;
        boolean isComplete;
        String sessionId;
        String crcStatus;
        int dataLength;
        int totalFramesAll;
        int totalBytesAll;
        String markerType;
        public String sourceCallsign;
        public String targetCallsign;
        public boolean isBeacon;
        public String grid6;
        // Contact remark shown as a separate line for received entries.
        public String contactNote;

        HistoryEntry(String direction, long timestamp, int freq, int snr, float dt,
                     int framesReceived, int totalFrames, String text,
                     String status, boolean isComplete, String sessionId,
                     String crcStatus, int dataLength,
                     int totalFramesAll, int totalBytesAll, String markerType,
                     boolean isBeacon, String grid6) {
            this.direction = direction;
            this.timestamp = timestamp;
            this.freq = freq;
            this.snr = snr;
            this.dt = dt;
            this.framesReceived = framesReceived;
            this.totalFrames = totalFrames;
            this.text = text;
            this.status = status;
            this.isComplete = isComplete;
            this.sessionId = sessionId;
            this.crcStatus = crcStatus;
            this.dataLength = dataLength;
            this.totalFramesAll = totalFramesAll;
            this.totalBytesAll = totalBytesAll;
            this.markerType = markerType;
            this.isBeacon = isBeacon;
            this.grid6 = grid6;
        }

        // Rich-text rendering. Colors and layout mirror the Python GUI:
        // header / body colors depend on direction, message type and status.
        // localCallsign is passed in because this class is static and
        // cannot access the outer MainActivity.callsign field directly.
        CharSequence getDisplayText(Context ctx, String localCallsign) {
            String timeStr = utcFullFormat.format(new Date(timestamp));
            SpannableStringBuilder sb = new SpannableStringBuilder();

            // Resolve the eight ANSI colors from resources.
            int colorBlack     = ContextCompat.getColor(ctx, R.color.ansi_black);
            int colorRedWarn   = ContextCompat.getColor(ctx, R.color.ansi_red);
            int colorGreenSoft = ContextCompat.getColor(ctx, R.color.ansi_green);
            int colorBlueSoft  = ContextCompat.getColor(ctx, R.color.ansi_blue);

            // Align with the Python reference: treat an entry as "self"
            // when either the source or the target callsign matches
            // the local station.
            boolean isSelf = (targetCallsign != null && targetCallsign.equals(localCallsign))
                    || (sourceCallsign != null && sourceCallsign.equals(localCallsign));
            boolean isRecvFail = false;
            if ("RX".equals(direction) && crcStatus != null) {
                String lc = crcStatus.toLowerCase(Locale.US);
                if (lc.contains("fail") || lc.contains("timeout")) {
                    isRecvFail = true;
                }
            }
            String statusLower = (status == null) ? "" : status.toLowerCase(Locale.US);
            boolean isTxFail = "TX".equals(direction) && isComplete
                    && (statusLower.contains("interrupted")
                    || statusLower.contains("cancelled")
                    || statusLower.contains("exception"));

            int headerColor;
            int bodyColor;
            if ("TX".equals(direction)) {
                if (isBeacon) {
                    headerColor = colorGreenSoft;
                    bodyColor = colorGreenSoft;
                } else if (!isComplete) {
                    headerColor = colorGreenSoft;
                    bodyColor = colorGreenSoft;
                } else if (isTxFail) {
                    headerColor = colorRedWarn;
                    bodyColor = colorRedWarn;
                } else {
                    headerColor = colorGreenSoft;
                    bodyColor = colorGreenSoft;
                }
            } else {
                if (isBeacon) {
                    // Python: received beacon uses black, not orange.
                    // Sent beacon (TX branch above) stays green.
                    headerColor = colorBlack;
                    bodyColor = colorBlack;
                } else if (isRecvFail) {
                    headerColor = colorRedWarn;
                    bodyColor = colorRedWarn;
                } else if (isSelf) {
                    headerColor = colorBlueSoft;
                    bodyColor = colorBlueSoft;
                } else {
                    headerColor = colorBlack;
                    bodyColor = colorBlack;
                }
            }

            // Header line.
            StringBuilder header = new StringBuilder();
            header.append("[").append(timeStr).append("] ");
            header.append("TX".equals(direction) ? "[TX]" : "[RX]").append(" ");
            header.append("[").append(localizeMarker(ctx, markerType)).append("] ");
            header.append("[").append(freq).append("Hz] ");
            String statusText = "";
            boolean cont = isContinuous(markerType);
            if (cont) {
                if ("RX".equals(direction)) {
                    if (!isComplete) {
                        statusText = ctx.getString(R.string.history_status_receiving);
                    } else if (crcStatus != null) {
                        if (crcStatus.equals("CRC OK") || crcStatus.equals("No_checksum")) {
                            statusText = ctx.getString(R.string.history_status_crc_ok);
                        } else if (crcStatus.equals("CRC fail")) {
                            statusText = ctx.getString(R.string.history_status_crc_fail);
                        } else if (crcStatus.equals("Timeout")) {
                            statusText = ctx.getString(R.string.history_status_timeout);
                        } else {
                            statusText = crcStatus;
                        }
                    } else {
                        statusText = (status != null) ? status : "";
                    }
                } else {
                    if (!isComplete) {
                        statusText = ctx.getString(R.string.history_status_sending);
                    } else if (isTxFail) {
                        statusText = ctx.getString(R.string.history_status_interrupted);
                    } else {
                        statusText = ctx.getString(R.string.history_status_done);
                    }
                }
            }
            if (!statusText.isEmpty()) {
                header.append("[").append(statusText).append("] ");
            }
            String progress = "";
            if (cont) {
                int totalF = (totalFramesAll > 0) ? totalFramesAll : totalFrames;
                if (totalF > 1) {
                    if ("RX".equals(direction)) {
                        progress = ctx.getString(R.string.history_progress_rx, framesReceived, dataLength);
                    } else {
                        int totalB = (totalBytesAll > 0) ? totalBytesAll : dataLength;
                        progress = ctx.getString(R.string.history_progress_tx,
                                framesReceived, totalF, dataLength, totalB);
                    }
                }
            }
            if (!progress.isEmpty()) {
                header.append(progress).append(" ");
            }
            String headerStr = header.toString().trim();

            int s1 = sb.length();
            sb.append(headerStr);
            sb.setSpan(new ForegroundColorSpan(headerColor), s1, sb.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

            // Contact note line, only for received entries with a known note.
            if ("RX".equals(direction) && sourceCallsign != null
                    && contactNote != null && !contactNote.isEmpty()) {
                int s2 = sb.length();
                sb.append("\n[").append(contactNote).append("]");
                // Contact note uses the same color as the body text
                // so the remark blends in with the main information.
                int noteColor = bodyColor;
                sb.setSpan(new ForegroundColorSpan(noteColor), s2, sb.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            // Body text, wrapped at 60 characters.
            // Python displays only the callsign for beacon entries:
            //   TX beacon -> local callsign
            //   RX beacon -> source callsign
            // Non-beacon entries keep the raw text.
            String bodyText;
            if (isBeacon && grid6 != null && !grid6.isEmpty()) {
                // New location-message beacon: display "CALL GRID".
                String call = "TX".equals(direction) ? localCallsign : sourceCallsign;
                if (call == null || call.isEmpty()) call = "?";
                bodyText = call + " " + grid6;
            } else if (isBeacon) {
                if ("TX".equals(direction)) {
                    bodyText = (localCallsign != null && !localCallsign.isEmpty())
                            ? localCallsign : "?";
                } else {
                    bodyText = (sourceCallsign != null && !sourceCallsign.isEmpty())
                            ? sourceCallsign : "?";
                }
            } else {
                bodyText = text;
            }
            if (bodyText != null && !bodyText.isEmpty()) {
                String[] lines = wrapText(bodyText, 60);
                int s3 = sb.length();
                for (String line : lines) {
                    sb.append("\n").append(line);
                }
                sb.setSpan(new ForegroundColorSpan(bodyColor), s3, sb.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            // Underline the whole entry when our own callsign is targeted.
            if (isSelf && !isRecvFail) {
                sb.setSpan(new UnderlineSpan(), 0, sb.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            return sb;
        }

        private String[] wrapText(String text, int width) {
            List<String> result = new ArrayList<>();
            for (int i = 0; i < text.length(); i += width) {
                int end = Math.min(i + width, text.length());
                result.add(text.substring(i, end));
            }
            return result.toArray(new String[0]);
        }

        private static boolean isContinuous(String m) {
            return "continuous".equals(m) || "\u8fde\u7eed\u5e27".equals(m);
        }

        private static String localizeMarker(Context ctx, String m) {
            if (m == null || m.isEmpty()) return ctx.getString(R.string.marker_unknown);
            switch (m) {
                case "continuous":
                case "\u8fde\u7eed\u5e27":
                    return ctx.getString(R.string.marker_continuous);
                case "single":
                case "\u5355\u72ec\u5e27":
                    return ctx.getString(R.string.marker_single);
                case "beacon":
                case "\u4fe1\u6807":
                    return ctx.getString(R.string.marker_beacon);
                case "free_text":
                case "\u81ea\u7531\u6587\u672c":
                    return ctx.getString(R.string.marker_free_text);
                default:
                    return m;
            }
        }
    }

    // ================================================================
    // HistoryAdapter
    // ================================================================
    private class HistoryAdapter extends BaseAdapter {
        @Override
        public int getCount() { return historyList.size(); }

        @Override
        public Object getItem(int position) { return historyList.get(position); }

        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv;
            if (convertView == null) {
                tv = new TextView(MainActivity.this);
                tv.setPadding(16, 8, 16, 8);
                tv.setTextSize(14);
                // Use a monospace typeface to match the Python reference,
                // which renders the history area with Consolas.
                tv.setTypeface(Typeface.MONOSPACE);
                tv.setLayoutParams(new ListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                tv = (TextView) convertView;
            }

            final HistoryEntry item = historyList.get(position);
            tv.getPaint().setFakeBoldText(false);
            tv.setMovementMethod(null);
            tv.setLongClickable(true);
            
            // Bind long click at the TextView level.
            // This guarantees long press triggers the menu without interfering with OnClickListener.
            tv.setOnLongClickListener(v -> {
                showHistoryOptionsDialog();
                return true;
            });

            // Rich text is fully built inside HistoryEntry.getDisplayText,
            // including per-line colors and the underline for self targets.
            CharSequence displayText = item.getDisplayText(MainActivity.this, callsign);
            tv.setText(displayText);

            if (item.direction.equals("TX")) {
                tv.setGravity(Gravity.END);
            } else {
                tv.setGravity(Gravity.START);
            }

            if (item.isBeacon && item.grid6 != null && !item.grid6.isEmpty()) {
                final String finalGrid = item.grid6.toUpperCase(Locale.US);
                // Beacon callsign becomes the map pin label:
                //   TX beacon -> local callsign
                //   RX beacon -> source callsign from the decoded payload
                final String finalLabel;
                if ("TX".equals(item.direction)) {
                    finalLabel = callsign;
                } else if (item.sourceCallsign != null
                        && !item.sourceCallsign.isEmpty()) {
                    finalLabel = item.sourceCallsign;
                } else {
                    finalLabel = "MyPoint";
                }
                tv.setOnClickListener(v -> openGridOnMap(finalGrid, finalLabel));
            } else {
                tv.setOnClickListener(null);
            }

            return tv;
        }

        public void updateOrAdd(HistoryEntry entry) {
            String key = entry.direction + "_" + entry.sessionId;
            Integer index = historyIndexMap.get(key);
            if (index != null) {
                historyList.set(index, entry);
                notifyDataSetChanged();
                lvHistory.smoothScrollToPosition(historyList.size() - 1);
            } else {
                historyList.add(entry);
                historyIndexMap.put(key, historyList.size() - 1);
                while (historyList.size() > MAX_DECODE_HISTORY) {
                    historyList.remove(0);
                    rebuildIndexMap();
                }
                notifyDataSetChanged();
                lvHistory.smoothScrollToPosition(historyList.size() - 1);
            }
        }

        private void rebuildIndexMap() {
            historyIndexMap.clear();
            for (int i = 0; i < historyList.size(); i++) {
                HistoryEntry e = historyList.get(i);
                String key = e.direction + "_" + e.sessionId;
                historyIndexMap.put(key, i);
            }
        }
    }

    // ---------- Transparent buffer ----------
    private class TransparentBuffer {
        int sessionId;
        long firstFreq;
        int firstSnr;
        float dt;
        long slotStartTime;
        List<byte[]> blocks = new ArrayList<>();
        byte[] allData = null;
        boolean isComplete = false;
        boolean hasEot = false;
        Handler timeoutHandler;
        Runnable timeoutRunnable;
        String historySessionId;
        // Slot-indexed tracking so missing 15-second slots between
        // arrivals become visible in the history view.
        Long firstSlotIndex = null;
        Long maxReceivedSlot = null;
        final HashMap<Long, byte[]> slots = new HashMap<>();

        TransparentBuffer(int sessionId, long freq, int snr, float dt, long slotStart) {
            this.sessionId = sessionId;
            this.firstFreq = freq;
            this.firstSnr = snr;
            this.dt = dt;
            this.slotStartTime = slotStart;
            this.historySessionId = "rx_" + System.currentTimeMillis();
            scheduleTimeout();
            updateHistoryPartial("RX", historySessionId, (int) freq, snr, dt,
                    0, -1, "", "receiving", false, null, 0, slotStart,
                    -1, 0, "continuous", false, null);
        }

        void appendBlock(byte[] d9, long slotIndex) {
            blocks.add(d9);
            if (firstSlotIndex == null) firstSlotIndex = slotIndex;
            if (maxReceivedSlot == null || slotIndex > maxReceivedSlot) {
                maxReceivedSlot = slotIndex;
            }
            slots.put(slotIndex, d9);
            resetTimeout();
            for (byte b : d9) {
                if (b == 0x04) { hasEot = true; break; }
            }
            int frameCount = blocks.size();
            // Rebuild the whole display text from the slot map after
            // every frame so a multi-byte UTF-8 character split across
            // frame boundaries is decoded only after its surrounding
            // bytes are joined. Missing slots are filled with the
            // localized "[frame missing]" marker.
            String partialText = buildDisplayText(true, false, false);
            int dataLen = partialText.getBytes(StandardCharsets.UTF_8).length;
            updateHistoryPartial("RX", historySessionId, (int) firstFreq, firstSnr, dt,
                    frameCount, -1, partialText, "receiving", false, null, dataLen, slotStartTime,
                    frameCount, dataLen, "continuous", false, null);
            if (frameCount == 1 || frameCount % 5 == 0) {
                appendStatusMessage(getString(R.string.status_rx_freq_progress, (int) firstFreq, frameCount),
                        getCurrentUtcTime());
            }
            tryFinish();
        }

        // Walk the slot range from the first received slot up to the
        // last expected slot. Contiguous received blocks are merged
        // into a byte buffer and decoded as a single UTF-8 segment;
        // every absent slot flushes that buffer and inserts the
        // localized "frame missing" marker. This preserves multi-byte
        // characters that span frame boundaries while making dropped
        // frames visible in place.
        String buildDisplayText(boolean stripEot, boolean stripCrc, boolean stripPadding) {
            if (firstSlotIndex == null) return "";

            long currentTimeMs = getCurrentUtcTime();
            long expectedCurrentSlot = (long) Math.floor((currentTimeMs + 7500.0) / 15000.0);
            long maxSlot = (maxReceivedSlot != null) ? maxReceivedSlot : firstSlotIndex;
            if (expectedCurrentSlot > maxSlot) {
                maxSlot = expectedCurrentSlot - 1;
            }
            // Cap the number of slots considered at MAX_FRAMES.
            maxSlot = Math.min(maxSlot, firstSlotIndex + MAX_FRAMES - 1);

            StringBuilder parts = new StringBuilder();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            boolean foundEot = false;

            for (long i = firstSlotIndex; i <= maxSlot && !foundEot; i++) {
                if (slots.containsKey(i)) {
                    byte[] block = slots.get(i);
                    if (stripEot) {
                        int pos = -1;
                        for (int j = 0; j < block.length; j++) {
                            if (block[j] == 0x04) { pos = j; break; }
                        }
                        if (pos != -1) {
                            buf.write(block, 0, pos);
                            foundEot = true;
                        } else {
                            buf.write(block, 0, block.length);
                        }
                    } else {
                        buf.write(block, 0, block.length);
                    }
                } else {
                    // Missing slot: flush the accumulated bytes as a
                    // UTF-8 segment, then insert the localized marker.
                    if (buf.size() > 0) {
                        parts.append(new String(buf.toByteArray(), StandardCharsets.UTF_8));
                        buf.reset();
                    }
                    parts.append(getString(R.string.frame_missing));
                }
            }

            if (buf.size() > 0) {
                byte[] bytes = buf.toByteArray();
                if (stripCrc && bytes.length > 0) {
                    byte[] tmp = new byte[bytes.length - 1];
                    System.arraycopy(bytes, 0, tmp, 0, tmp.length);
                    bytes = tmp;
                }
                if (stripPadding && bytes.length > 0) {
                    int len = bytes.length;
                    while (len > 0 && bytes[len - 1] == 0x20) len--;
                    byte[] tmp = new byte[len];
                    System.arraycopy(bytes, 0, tmp, 0, len);
                    bytes = tmp;
                }
                parts.append(new String(bytes, StandardCharsets.UTF_8));
            }

            return parts.toString();
        }

        void tryFinish() {
            if (isComplete) return;
            if (!hasEot) return;
            // Concatenate received bytes in arrival order and cut at
            // the first EOT, matching the Python reference which joins
            // self.blocks and slices up to EOT. A missing middle slot
            // corrupts this stream; that is exactly what makes the
            // CRC check fail.
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            outer:
            for (byte[] block : blocks) {
                for (byte b : block) {
                    if (b == 0x04) break outer;
                    baos.write(b);
                }
            }
            allData = baos.toByteArray();
            if (allData.length == 0) {
                finish(getString(R.string.status_rx_empty_msg),
                       getString(R.string.status_rx_empty_data), "No data", 0);
                return;
            }
            byte[] raw = new byte[allData.length - 1];
            System.arraycopy(allData, 0, raw, 0, allData.length - 1);
            byte recvCrc = allData[allData.length - 1];
            byte calcCrc = crc8(raw);
            String status, marker, crcStatus;
            if (calcCrc == recvCrc) {
                status = "CRC OK";
                marker = "[✓CRC OK]";
                crcStatus = "CRC OK";
            } else {
                status = "CRC fail (calc " + String.format("%02x", calcCrc) +
                        ", recv " + String.format("%02x", recvCrc) + ")";
                marker = "[✗CRC fail]";
                crcStatus = "CRC fail";
            }
            String text = (calcCrc == recvCrc)
                    ? buildDisplayText(true, true, true)
                    : buildDisplayText(true, true, false);
            int dataLen;
            if (calcCrc == recvCrc) {
                dataLen = slots.size() * 9 - 2;
            } else {
                dataLen = text.getBytes(StandardCharsets.UTF_8).length;
            }
            String markerTypeLocal = "continuous";
            updateHistoryPartial("RX", historySessionId, (int) firstFreq, firstSnr, dt,
                    blocks.size(), blocks.size(), text, marker, true, crcStatus, dataLen, slotStartTime,
                    blocks.size(), dataLen, markerTypeLocal, false, null);
            appendStatusMessage(getString(R.string.status_rx_done, (int) firstFreq, blocks.size(), status),
                    getCurrentUtcTime());
            synchronized (transparentBuffers) {
                transparentBuffers.remove(historySessionId);
            }
            isComplete = true;
            cancelTimeout();
        }

        void finish(String text, String marker, String crcStatus, int dataLen) {
            if (isComplete) return;
            isComplete = true;
            cancelTimeout();
            updateHistoryPartial("RX", historySessionId, (int) firstFreq, firstSnr, dt,
                    blocks.size(), blocks.size(), text, marker, true, crcStatus, dataLen, slotStartTime,
                    blocks.size(), dataLen, "continuous", false, null);
            synchronized (transparentBuffers) {
                transparentBuffers.remove(historySessionId);
            }
        }

        void onTimeout() {
            if (isComplete) return;
            isComplete = true;
            cancelTimeout();
            String text = buildDisplayText(true, false, false);
            int dataLen = text.getBytes(StandardCharsets.UTF_8).length;
            updateHistoryPartial("RX", historySessionId, (int) firstFreq, firstSnr, dt,
                    blocks.size(), -1, text, "timeout", true, "Timeout", dataLen, slotStartTime,
                    blocks.size(), dataLen, "continuous", false, null);
            appendStatusMessage(getString(R.string.status_rx_timeout, (int) firstFreq, blocks.size()),
                    getCurrentUtcTime());
            synchronized (transparentBuffers) {
                transparentBuffers.remove(historySessionId);
            }
        }

        void scheduleTimeout() {
            timeoutHandler = new Handler(Looper.getMainLooper());
            timeoutRunnable = this::onTimeout;
            // Timeout scales with the number of 15-second slots covered
            // by the received frames. Missing slots count toward the
            // span, so a late-starting session still gets enough time
            // to collect its remaining frames, while a nearly complete
            // one finishes quickly instead of waiting a fixed 4 slots.
            //
            //   span       = maxReceivedSlot - firstSlotIndex + 1
            //   waitSlots  = MAX_FRAMES - span  (clamped at 0)
            //   delayMs    = waitSlots * 15 * 1000
            //
            // resetTimeout() calls scheduleTimeout() after every frame,
            // so the wait above is measured from the most recent
            // arrival.
            int span;
            if (firstSlotIndex != null && maxReceivedSlot != null) {
                span = (int) (maxReceivedSlot - firstSlotIndex + 1);
            } else {
                span = 0;
            }
            int waitSlots = MAX_FRAMES - span;
            if (waitSlots < 0) waitSlots = 0;
            long delayMs = waitSlots * 15L * 1000L;
            timeoutHandler.postDelayed(timeoutRunnable, delayMs);
        }

        void cancelTimeout() {
            if (timeoutHandler != null && timeoutRunnable != null) {
                timeoutHandler.removeCallbacks(timeoutRunnable);
                timeoutHandler = null;
                timeoutRunnable = null;
            }
        }

        private void resetTimeout() {
            cancelTimeout();
            scheduleTimeout();
        }
    }

    // ---------- Helper methods ----------
    private static boolean isValidCallsign(String call) {
        if (call == null) return false;
        String c = call.trim().toUpperCase(Locale.US);
        if (c.length() < 1 || c.length() > 9) return false;
        return c.matches("[A-Z0-9]+");
    }

    private static boolean isValidCallsignStrict(String call) {
        if (call == null) return false;
        String c = call.trim().toUpperCase(Locale.US);
        // Python rule: 3~7 chars; 1~2 prefix + 1 digit + 0~4 letters; prefix has at least one letter
        if (c.length() < 3 || c.length() > 7) return false;
        if (!c.matches("[A-Z0-9]{1,2}[0-9][A-Z]{0,4}")) return false;
        int firstDigit = -1;
        for (int i = 0; i < c.length(); i++) {
            if (Character.isDigit(c.charAt(i))) {
                firstDigit = i;
                break;
            }
        }
        if (firstDigit <= 0) return false;
        String prefix = c.substring(0, firstDigit);
        return prefix.matches(".*[A-Z].*");
    }

    private String[] extractCallsigns(String text) {
        if (TextUtils.isEmpty(text)) return new String[]{null, null};

        String trimmed = text.trim().toUpperCase(Locale.US);
        String source = null, target = null;

        int firstSpace = trimmed.indexOf(' ');
        if (firstSpace != -1) {
            String srcPart = trimmed.substring(0, firstSpace).trim();
            if (isValidCallsignStrict(srcPart)) {
                source = srcPart;
            }
        } else {
            for (int len = Math.min(7, trimmed.length()); len >= 3; len--) {
                String cand = trimmed.substring(0, len);
                if (isValidCallsignStrict(cand)) {
                    source = cand;
                    break;
                }
            }
        }

        if (source != null && firstSpace != -1) {
            int secondSpace = trimmed.indexOf(' ', firstSpace + 1);
            String tgtPart;
            if (secondSpace == -1) {
                tgtPart = trimmed.substring(firstSpace + 1).trim();
            } else {
                tgtPart = trimmed.substring(firstSpace + 1, secondSpace).trim();
            }
            if (isValidCallsignStrict(tgtPart)) {
                target = tgtPart;
            }
        }

        return new String[]{source, target};
    }

    private void updateHistoryPartial(String direction, String sessionId,
                                      int freq, int snr, float dt,
                                      int framesReceived, int totalFrames,
                                      String text, String status, boolean complete,
                                      String crcStatus, int dataLength, long timestamp,
                                      int totalFramesAll, int totalBytesAll,
                                      String markerType,
                                      boolean isBeacon, String grid6) {
        long finalTimestamp = timestamp;
        if ("TX".equals(direction) && sessionId != null) {
            Long cached = sendSessionFirstTimestamp.get(sessionId);
            if (cached != null) {
                finalTimestamp = cached;
            } else {
                sendSessionFirstTimestamp.put(sessionId, timestamp);
                finalTimestamp = timestamp;
            }
        }

        final long finalTimestampFinal = finalTimestamp;
        final String finalText = (text == null) ? "" : text;
        final boolean finalIsBeacon = isBeacon;

        runOnUiThread(() -> {
            String source = null, target = null;
            if ("RX".equals(direction)) {
                String[] calls = extractCallsigns(finalText);
                source = calls[0];
                target = calls[1];
            }

            HistoryEntry entry = new HistoryEntry(
                    direction, finalTimestampFinal, freq, snr, dt,
                    framesReceived, totalFrames,
                    finalText, status, complete, sessionId, crcStatus, dataLength,
                    totalFramesAll, totalBytesAll, markerType,
                    finalIsBeacon, grid6
            );
            entry.sourceCallsign = source;
            entry.targetCallsign = target;
            // Attach the contact remark (if any) so it can be rendered as a
            // separate line under the header.
            if ("RX".equals(direction) && source != null) {
                entry.contactNote = lookupContactNote(source);
            }

            historyAdapter.updateOrAdd(entry);

            if (complete) {
                saveHistoryToFile(entry);
            }

            // Match Python: try to auto-save the source callsign on every
            // received entry, including partial ones.
            if ("RX".equals(direction) && source != null) {
                if (isValidCallsignStrict(source)) {
                    if (!contactExists(source)) {
                        // Auto-added remark uses the localized format string:
                        // "Auto-added on <date>" / "自动添加于 <date>".
                        String note = getString(R.string.contact_auto_remark_fmt,
                                utcDateOnly.format(new Date(finalTimestampFinal)));
                        contactList.add(new Contact(source, note));
                        saveContacts();
                        appendStatusMessage(getString(R.string.status_auto_saved_contact, source),
                                finalTimestampFinal);
                    }
                } else {
                    appendStatusMessage(getString(R.string.status_src_callsign_invalid, source),
                            finalTimestampFinal);
                }
            }
        });
    }

    private static byte crc8(byte[] data) {
        final int poly = 0x07;
        int crc = 0x00;
        for (byte b : data) {
            crc ^= (b & 0xFF);
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x80) != 0)
                    crc = (crc << 1) ^ poly;
                else
                    crc <<= 1;
                crc &= 0xFF;
            }
        }
        return (byte) crc;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    private static int extractF2(String line) {
        int idx = line.indexOf("f2=");
        if (idx == -1) idx = line.indexOf("f2:");
        if (idx == -1) return -1;
        int start = idx + 3;
        int end = start;
        while (end < line.length() && Character.isDigit(line.charAt(end))) end++;
        if (end > start) {
            return Integer.parseInt(line.substring(start, end));
        }
        return -1;
    }

    private static byte[] extractB72Bytes(String line) {
        int idx = line.indexOf("b72=0x");
        if (idx == -1) idx = line.indexOf("b72:0x");
        if (idx == -1) return null;
        int start = idx + 6;
        int end = start;
        while (end < line.length() && (Character.isDigit(line.charAt(end)) ||
                (line.charAt(end) >= 'a' && line.charAt(end) <= 'f') ||
                (line.charAt(end) >= 'A' && line.charAt(end) <= 'F'))) {
            end++;
        }
        if (end == start) return null;
        String hex = line.substring(start, end);
        while (hex.length() < 18) hex = "0" + hex;
        if (hex.length() > 18) hex = hex.substring(hex.length() - 18);
        byte[] bytes = new byte[9];
        try {
            for (int i = 0; i < 9; i++) {
                String byteHex = hex.substring(i * 2, i * 2 + 2);
                bytes[i] = (byte) Integer.parseInt(byteHex, 16);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return bytes;
    }

    private static int extractFreq(String line) {
        Pattern p = Pattern.compile("(\\d+)Hz");
        Matcher m = p.matcher(line);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                return 1500;
            }
        }
        return 1500;
    }

    private static int extractSnr(String line) {
        Pattern p = Pattern.compile("SNR:([+-]?\\d+)");
        Matcher m = p.matcher(line);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                return -30;
            }
        }
        return -30;
    }

    private float extractDt(String line) {
        Pattern p = Pattern.compile("DT:\\s*([+-]?\\d+\\.?\\d*)");
        Matcher m = p.matcher(line);
        if (m.find()) {
            try {
                return Float.parseFloat(m.group(1));
            } catch (NumberFormatException e) { /* ignore */ }
        }
        return 0.0f;
    }

    // ---------- JNI ----------
    public static class FT8Decoder {
        static { System.loadLibrary("ft8_jni"); }
        public static native String decodeFt8(short[] samples, int sampleRate);
    }

    public static class FT8Encoder {
        static { System.loadLibrary("ft8_jni"); }
        public static native short[] encodeTransparent(int f2, String hexData, int freqHz, int sampleRate);
        public static native short[] encodeFreeText(String text, int freqHz, int sampleRate);
    }

    // Resolve one of the eight ANSI colors from resources.
    private int ansiColor(int resId) {
        return ContextCompat.getColor(this, resId);
    }

    // ---------- Activity lifecycle ----------
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvUtcTime = findViewById(R.id.tv_utc_time);
        tvStatus = findViewById(R.id.tv_status);
        scrollView = findViewById(R.id.scrollView);
        tvLedStatus = findViewById(R.id.tv_led_status);
        btnTransmitStop = findViewById(R.id.btn_transmit_stop);
        etTxMessage = findViewById(R.id.et_tx_message);
        etTxFreq = findViewById(R.id.et_tx_freq);
        lvHistory = findViewById(R.id.lv_history);
        btnCallsign = findViewById(R.id.btn_callsign);
        btnBeacon = findViewById(R.id.btn_beacon);
        btnContacts = findViewById(R.id.btn_contacts);
        btnAbout = findViewById(R.id.btn_about);
        chkPreambleNoise = findViewById(R.id.chk_preamble_noise);
        tvByteCount = findViewById(R.id.tv_byte_count);
        tvFrameCount = findViewById(R.id.tv_frame_count);
        btnGrid = findViewById(R.id.btn_grid);

        // Android 11+ requires MANAGE_EXTERNAL_STORAGE to write
        // directly to Documents/FT8 Plus/ with the File API. The
        // previous MediaStore-based approach was unreliable on
        // MIUI, so the app now uses direct File access and must
        // prompt for this permission on first launch. If the user
        // denies, the app still starts but persistence will fail
        // on Android 11+ until the permission is granted from
        // system settings.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent allFilesIntent = new Intent(
                            android.provider.Settings
                                    .ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    allFilesIntent.setData(
                            Uri.parse("package:" + getPackageName()));
                    startActivity(allFilesIntent);
                } catch (Exception e) {
                    Log.w(TAG, "Cannot open all-files settings page", e);
                }
            }
        }

        // Request runtime permissions only after every findViewById()
        // call. ActivityCompat.requestPermissions() may invoke
        // onRequestPermissionsResult() synchronously (e.g. on MIUI
        // relaunches or when another request is already in flight,
        // as shown by the "Can request only one set of permissions"
        // warning). That handler calls appendStatusMessage() ->
        // tvStatus.getText(), so the views must already exist.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            List<String> permissions = new ArrayList<>();
            permissions.add(Manifest.permission.RECORD_AUDIO);
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                // Android 10 and below: WRITE_EXTERNAL_STORAGE is needed
                // to write directly to the public Documents directory.
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
            List<String> missing = new ArrayList<>();
            for (String p : permissions) {
                if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                    missing.add(p);
                }
            }
            if (!missing.isEmpty()) {
                ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), PERMISSION_REQ_CODE);
            }
        }

        chkPreambleNoise.setEnabled(true);
        chkPreambleNoise.setChecked(preambleNoise);
        chkPreambleNoise.setOnCheckedChangeListener((buttonView, isChecked) -> {
            preambleNoise = isChecked;
            saveConfig();
            appendStatusMessage(getString(isChecked
                    ? R.string.status_preamble_noise_on
                    : R.string.status_preamble_noise_off), getCurrentUtcTime());
        });

        // Initialize the persistence store and create all four files
        // immediately so they exist from the first launch. config.txt
        // is then rewritten with defaults by loadConfigWithFallback().
        getFileStore();
        ensurePersistenceFiles();
        loadConfigWithFallback();
        loadContacts();

        etTxFreq.setText(String.valueOf(txFreq));
        etTxFreq.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                String str = s.toString().trim();
                if (!str.isEmpty()) {
                    try {
                        int val = Integer.parseInt(str);
                        if (val >= 300 && val <= 3000) {
                            txFreq = val;
                            saveConfig();
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        });

        btnCallsign.setText(callsign.isEmpty() ? getString(R.string.btn_callsign_anonymous) : callsign);
        btnGrid.setText(grid.isEmpty() ? getString(R.string.gui_grid) : grid);
        chkPreambleNoise.setChecked(preambleNoise);

        // Use the localized string resource instead of a hard-coded emoji,
        // so the beacon button label follows the system language.
        btnBeacon.setText("📡");
        btnBeacon.setBackgroundColor(ansiColor(R.color.ansi_black));
        btnBeacon.invalidate();

        // Input field starts completely empty.
        etTxMessage.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                updateInputStats();
            }
        });
        updateInputStats();

        etTxMessage.setOnLongClickListener(v -> {
            String[] items = {
                    getString(R.string.dlg_input_copy),
                    getString(R.string.dlg_input_paste),
                    getString(R.string.dlg_input_clear)
            };
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle(R.string.dlg_choose_action)
                    .setItems(items, (dialog, which) -> {
                        switch (which) {
                            case 0:
                                int start = etTxMessage.getSelectionStart();
                                int end = etTxMessage.getSelectionEnd();
                                String text;
                                if (start != end) {
                                    text = etTxMessage.getText().toString().substring(start, end);
                                } else {
                                    text = etTxMessage.getText().toString();
                                }
                                if (!TextUtils.isEmpty(text)) {
                                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                                    ClipData clip = ClipData.newPlainText("FT8 Message", text);
                                    clipboard.setPrimaryClip(clip);
                                    showToast(R.string.toast_copied);
                                } else {
                                    showToast(R.string.toast_nothing_to_copy);
                                }
                                break;
                            case 1:
                                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                                if (clipboard.hasPrimaryClip()) {
                                    ClipData clip = clipboard.getPrimaryClip();
                                    if (clip != null && clip.getItemCount() > 0) {
                                        CharSequence paste = clip.getItemAt(0).getText();
                                        if (!TextUtils.isEmpty(paste)) {
                                            int selStart = etTxMessage.getSelectionStart();
                                            int selEnd = etTxMessage.getSelectionEnd();
                                            if (selStart != selEnd) {
                                                etTxMessage.getText().replace(selStart, selEnd, paste);
                                            } else {
                                                etTxMessage.getText().insert(selStart, paste);
                                            }
                                            updateInputStats();
                                            showToast(R.string.toast_pasted);
                                        }
                                    }
                                } else {
                                    showToast(R.string.toast_clipboard_empty);
                                }
                                break;
                            case 2:
                                etTxMessage.setText("");
                                updateInputStats();
                                showToast(R.string.toast_cleared);
                                break;
                        }
                    })
                    .setNegativeButton(R.string.dlg_cancel, null)
                    .show();
            return true;
        });

        historyAdapter = new HistoryAdapter();
        lvHistory.setAdapter(historyAdapter);
        
        // Handle long click on the ListView itself (covers blank space below items)
        lvHistory.setOnLongClickListener(v -> {
            showHistoryOptionsDialog();
            return true;
        });

        

        mainHandler.post(utcDashboardRunnable);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startBackgroundMemoryRx();
            syncNtp();
        }
        setLedReceive();

        btnTransmitStop.setOnClickListener(v -> {
            if (isTransmitting.get()) {
                stopTransmission();
            } else {
                startFT8Transmission();
            }
        });

        btnCallsign.setOnClickListener(v -> showCallsignDialog());
        btnBeacon.setOnClickListener(v -> toggleBeacon());
        btnGrid.setOnClickListener(v -> showGridDialog());
        btnContacts.setOnClickListener(v -> showContactsDialog());
        btnAbout.setOnClickListener(v -> showAboutDialog());

        tvStatus.setOnLongClickListener(v -> {
            String[] items = {
                    getString(R.string.dlg_input_copy),
                    getString(R.string.dlg_input_clear)
            };
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle(R.string.dlg_status_title)
                    .setItems(items, (dialog, which) -> {
                        if (which == 0) {
                            String content = tvStatus.getText().toString();
                            if (!TextUtils.isEmpty(content)) {
                                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                                ClipData clip = ClipData.newPlainText("FT8 Status", content);
                                clipboard.setPrimaryClip(clip);
                                showToast(R.string.toast_status_copied);
                            } else {
                                showToast(R.string.toast_status_empty);
                            }
                        } else if (which == 1) {
                            tvStatus.setText("");
                            // Also delete status.txt so the old log
                            // does not reappear after the next app launch.
                            getFileStore().delete(STATUS_FILE_NAME);
                            getFileStore().ensureAllFiles(new String[]{STATUS_FILE_NAME});
                            showToast(R.string.toast_status_cleared);
                        }
                    })
                    .setNegativeButton(R.string.dlg_cancel, null)
                    .show();
            return true;
        });

        loadStatusFromFile();
        loadHistoryFromFile();

        // Ensure the TX button starts with the ▶ symbol and green
        // background, matching updateButtonState()'s idle branch.
        updateButtonState();
    }

    // ========== NTP sync ==========
    // Retry policy: after a successful sync the offset is kept for the
    // lifetime of the process; after a failure the sync is re-attempted
    // every 30 seconds until it succeeds. The previous version set
    // ntpSyncAttempted = true before attempting, so a single failure on
    // an emulator left the offset at 0 forever and every slot window
    // was shifted by the system-clock drift (about 2.5 s on MuMu),
    // cutting ~17% of every slot's signal energy and making weak-signal
    // decoding fail.
    private static final long NTP_RETRY_INTERVAL_MS = 30000L;
    private static final int NTP_TIMEOUT_MS = 5000;

    private void syncNtp() {
        synchronized (ntpLock) {
            if (ntpSyncAttempted) return;
            ntpSyncAttempted = true;
        }
        new Thread(() -> {
            // Try both geographically close Chinese NTP servers and a
            // couple of well-known anycast servers. cn.pool.ntp.org is
            // often slow behind MuMu's NAT; aliyun and ntsc are more
            // reliable from mainland China.
            String[] servers = {
                    "cn.pool.ntp.org",
                    "ntp.aliyun.com",
                    "ntp.ntsc.ac.cn",
                    "pool.ntp.org",
                    "time.cloudflare.com"
            };
            for (String server : servers) {
                DatagramSocket socket = null;
                try {
                    socket = new DatagramSocket();
                    socket.setSoTimeout(NTP_TIMEOUT_MS);
                    byte[] sendPacket = new byte[48];
                    sendPacket[0] = 0x1B;
                    InetAddress address = InetAddress.getByName(server);
                    DatagramPacket packet = new DatagramPacket(sendPacket, sendPacket.length, address, 123);
                    long start = System.currentTimeMillis();
                    socket.send(packet);
                    byte[] recvBuffer = new byte[48];
                    DatagramPacket recvPacket = new DatagramPacket(recvBuffer, recvBuffer.length);
                    socket.receive(recvPacket);
                    long end = System.currentTimeMillis();
                    socket.close();

                    long ntpHigh = 0, ntpLow = 0;
                    for (int i = 40; i < 44; i++) {
                        ntpHigh <<= 8;
                        ntpHigh |= (recvBuffer[i] & 0xFF);
                    }
                    for (int i = 44; i < 48; i++) {
                        ntpLow <<= 8;
                        ntpLow |= (recvBuffer[i] & 0xFF);
                    }
                    long ntpEpoch = 2208988800L;
                    double ntpSeconds = ntpHigh + (ntpLow / 4294967296.0);
                    double ntpMillis = (ntpSeconds - ntpEpoch) * 1000.0;
                    long rtt = end - start;
                    double serverTime = ntpMillis + (rtt / 2.0);
                    double offset = serverTime - System.currentTimeMillis();
                    ntpOffset = offset;
                    // Surface the measured offset in logcat so the
                    // system-clock drift can be read straight from a
                    // log dump, without having to reason about it.
                    Log.i(TAG, "NTP offset from " + server
                            + ": " + Math.round(offset) + " ms (rtt "
                            + rtt + " ms)");
                    String msg = getString(R.string.status_ntp_sync_ok, server, offset / 1000.0);
                    runOnUiThread(() -> {
                        appendStatusMessage(msg, getCurrentUtcTime());
                        showToast(getString(R.string.toast_time_synced, offset / 1000.0));
                    });
                    return;
                } catch (Exception e) {
                    Log.w(TAG, "NTP 同步失败 (" + server + "): " + e.getMessage());
                    if (socket != null) socket.close();
                }
            }
            // All servers failed. Clear the "attempted" flag and try
            // again later so a transient network problem does not lock
            // the app into a drifting clock for the whole session.
            ntpOffset = 0.0;
            synchronized (ntpLock) {
                ntpSyncAttempted = false;
            }
            runOnUiThread(() -> {
                appendStatusMessage(getString(R.string.status_ntp_sync_fail), getCurrentUtcTime());
                showToast(R.string.toast_ntp_failed);
            });
            mainHandler.postDelayed(this::syncNtp, NTP_RETRY_INTERVAL_MS);
        }).start();
    }

    private long getCurrentUtcTime() {
        return System.currentTimeMillis() + (long) ntpOffset;
    }

    // ---------- Beacon toggle ----------
    private void toggleBeacon() {
        if (!isRecording.get()) {
            showToast(R.string.toast_start_receiver_first);
            return;
        }
        if (TextUtils.isEmpty(callsign) || !isValidCallsignStrict(callsign)) {
            Log.w(TAG, "toggleBeacon rejected: callsign='" + callsign + "'");
            showToast(R.string.toast_set_callsign_first);
            return;
        }
        if (grid == null || grid.isEmpty() || !isValidGrid8(grid)) {
            Log.w(TAG, "toggleBeacon rejected: grid='" + grid + "'");
            showToast(R.string.dlg_grid_required_beacon);
            return;
        }
        beaconEnabled = !beaconEnabled;
        if (beaconEnabled) {
            beaconStop.set(false);
            nextBeaconSlot = getNextSlotStart(getCurrentUtcTime());
            beaconThread = new Thread(this::beaconWorker);
            beaconThread.start();
            btnBeacon.setBackgroundColor(ansiColor(R.color.ansi_green));
            btnBeacon.invalidate();
            // Match the Python version: the input field is not locked.
            // Beacon sends only when the field is empty, and skips
            // the slot when the user has typed something.
            appendStatusMessage(getString(R.string.status_beacon_on, callsign + " " + grid),
                    getCurrentUtcTime());
        } else {
            beaconStop.set(true);
            if (beaconThread != null) {
                try { beaconThread.interrupt(); beaconThread.join(1000); } catch (InterruptedException e) {}
                beaconThread = null;
            }
            btnBeacon.setBackgroundColor(ansiColor(R.color.ansi_black));
            btnBeacon.invalidate();
            appendStatusMessage(getString(R.string.status_beacon_off), getCurrentUtcTime());
        }
    }

    // ===== Beacon worker thread (exception-safe, handles negative waits) =====
    private void beaconWorker() {
        Log.d(TAG, "Beacon worker started");
        while (!beaconStop.get() && !Thread.interrupted()) {
            try {
                long now = getCurrentUtcTime();
                if (nextBeaconSlot == 0) {
                    nextBeaconSlot = getNextSlotStart(now);
                }
                long wait = nextBeaconSlot - now;
                if (wait > 0) {
                    Log.d(TAG, "Beacon: waiting " + wait + " ms until " + nextBeaconSlot);
                    Thread.sleep(wait);
                } else if (wait < 0) {
                    Log.w(TAG, "Beacon: wait negative (" + wait + "), re-aligning");
                    nextBeaconSlot = getNextSlotStart(now);
                    continue;
                }
                if (beaconStop.get()) break;

                String beaconText = packLocationMessage(callsign, grid);
                if (beaconText == null) {
                    Log.w(TAG, "Beacon: packLocationMessage returned null "
                            + "for call='" + callsign + "' grid='" + grid + "', stopping beacon");
                    // Something went wrong between toggleBeacon() and now.
                    // Stop the beacon and tell the user instead of
                    // silently skipping every slot forever.
                    beaconStop.set(true);
                    appendStatusMessage(getString(R.string.dlg_grid_required_beacon),
                            getCurrentUtcTime());
                    break;
                }
                if (isInputAvailableForBeacon(beaconText) && !isTransmitting.get()) {
                    Log.d(TAG, "Beacon: sending at slot " + nextBeaconSlot);
                    runOnUiThread(() -> {
                        etTxMessage.setText(beaconText);
                        etTxMessage.setSelection(beaconText.length());
                        updateInputStats();
                    });
                    sendFreeText(beaconText, txFreq, nextBeaconSlot, true);
                } else {
                    String inputText = etTxMessage.getText().toString().trim();
                    Log.d(TAG, "Beacon: skipped (input=\"" + inputText + "\", transmitting=" + isTransmitting.get() + ")");
                }
                nextBeaconSlot += BEACON_INTERVAL_SLOTS * 15000L;
                Log.d(TAG, "Beacon: next slot set to " + nextBeaconSlot);
            } catch (InterruptedException e) {
                Log.w(TAG, "Beacon worker interrupted");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                Log.e(TAG, "Beacon worker error", e);
                appendStatusMessage(getString(R.string.status_beacon_exception, e.getMessage()),
                        getCurrentUtcTime());
            }
        }
        Log.d(TAG, "Beacon worker stopped");
    }

    private boolean isInputAvailableForBeacon(String beaconText) {
        String current = etTxMessage.getText().toString().trim();
        // Placeholder text is treated as empty input.
        return current.isEmpty()
                || current.equals(beaconText)
                ;
    }

    private long getNextSlotStart(long now) {
        return ((now + 15000) / 15000) * 15000;
    }

    private void updateInputStats() {
        String data = etTxMessage.getText().toString().trim();
        
        if (TextUtils.isEmpty(data)) {
            // Match the Python reference: an empty input shows
            // "Bytes: 0" without the denominator.
            tvByteCount.setText(R.string.label_bytes_empty);
            tvFrameCount.setText(getString(R.string.label_frames_count, 0));
            tvByteCount.setTextColor(ansiColor(R.color.ansi_black));
            tvFrameCount.setTextColor(ansiColor(R.color.ansi_black));
            return;
        }

        if (isFreeTextCompatible(data)) {
            tvByteCount.setText(R.string.label_free_text);
            tvFrameCount.setText(R.string.label_single_frame);
            tvByteCount.setTextColor(ansiColor(R.color.ansi_blue));
            tvFrameCount.setTextColor(ansiColor(R.color.ansi_blue));
            return;
        }

        byte[] utf8Bytes = data.getBytes(StandardCharsets.UTF_8);
        int byteCount = utf8Bytes.length;
        int frameCount;
        if (byteCount <= 9) {
            frameCount = 1;
        } else {
            int payloadLen = byteCount + 2;
            frameCount = (int) Math.ceil(payloadLen / 9.0);
        }

        tvByteCount.setText(getString(R.string.label_bytes_count, byteCount, MAX_RAW_DATA_BYTES));
        tvFrameCount.setText(getString(R.string.label_frames_count, frameCount));

        if (byteCount > MAX_RAW_DATA_BYTES) {
            tvByteCount.setTextColor(ansiColor(R.color.ansi_red));
            tvFrameCount.setTextColor(ansiColor(R.color.ansi_red));
        } else if (byteCount == 0) {
            tvByteCount.setTextColor(ansiColor(R.color.ansi_black));
            tvFrameCount.setTextColor(ansiColor(R.color.ansi_black));
        } else {
            tvByteCount.setTextColor(ansiColor(R.color.ansi_green));
            tvFrameCount.setTextColor(ansiColor(R.color.ansi_green));
        }
    }

    // ---------- Persistence ----------
    // FileStore abstracts the storage backend so the same MainActivity
    // code can persist to the public Documents/FT8 Plus directory on
    // Android 10+ (via MediaStore) and to a direct File path on older
    // releases, with an internal-storage fallback when the legacy
    // storage permission is missing.
    private interface FileStore {
        void ensureAllFiles(String[] names);
        boolean exists(String name);
        String readText(String name);
        void writeText(String name, String content);
        void appendLine(String name, String line);
        void trimLines(String name, int maxLines);
        void delete(String name);
        String getDisplayPath();
    }

    private static class LegacyFileStore implements FileStore {
        private final File dir;

        LegacyFileStore(File dir) {
            this.dir = dir;
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
        }

        @Override
        public void ensureAllFiles(String[] names) {
            for (String n : names) {
                File f = new File(dir, n);
                if (!f.exists()) {
                    try {
                        //noinspection ResultOfMethodCallIgnored
                        f.createNewFile();
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        @Override
        public boolean exists(String name) {
            return new File(dir, name).exists();
        }

        @Override
        public String readText(String name) {
            File f = new File(dir, name);
            if (!f.exists()) return "";
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(line);
                }
                return sb.toString();
            } catch (IOException e) {
                Log.w(TAG, "LegacyFileStore read failed: " + name, e);
                return "";
            }
        }

        @Override
        public void writeText(String name, String content) {
            File f = new File(dir, name);
            try (FileOutputStream fos = new FileOutputStream(f, false)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                Log.w(TAG, "LegacyFileStore write failed: " + name, e);
            }
        }

        @Override
        public void appendLine(String name, String line) {
            File f = new File(dir, name);
            try (FileOutputStream fos = new FileOutputStream(f, true);
                 OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                osw.write(line);
                osw.write('\n');
            } catch (IOException e) {
                Log.w(TAG, "LegacyFileStore append failed: " + name, e);
            }
        }

        @Override
        public void trimLines(String name, int maxLines) {
            File f = new File(dir, name);
            if (!f.exists()) return;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), StandardCharsets.UTF_8))) {
                List<String> lines = new ArrayList<>();
                String line;
                while ((line = r.readLine()) != null) lines.add(line);
                if (lines.size() <= maxLines) return;
                lines = lines.subList(lines.size() - maxLines, lines.size());
                try (FileOutputStream fos = new FileOutputStream(f, false);
                     OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                    for (String l : lines) {
                        osw.write(l);
                        osw.write('\n');
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "LegacyFileStore trim failed: " + name, e);
            }
        }

        @Override
        public void delete(String name) {
            File f = new File(dir, name);
            if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }

        @Override
        public String getDisplayPath() {
            return dir.getAbsolutePath();
        }
    }

    private static class DirectFileStore implements FileStore {
        private final Context context;

        public DirectFileStore(Context context) {
            this.context = context;
        }

        // Plain File-backed store. Reads and writes go straight to
        // Documents/FT8 Plus/, with no MediaStore insert / scan
        // cycle. This bypasses the MIUI MediaProvider bug where
        // resolver.insert() throws
        // "UNIQUE constraint failed: files._data" whenever the
        // file already exists, and the scan fallback then fails to
        // cache the returned URI, so every subsequent write is
        // silently dropped.
        //
        // Requirements:
        //   Android 10 and below : WRITE_EXTERNAL_STORAGE
        //   Android 11 and above : MANAGE_EXTERNAL_STORAGE
        // Both are declared in AndroidManifest.xml. The Activity
        // prompts for MANAGE_EXTERNAL_STORAGE on first launch if it
        // has not been granted yet.

        private File getDir() {
            // 1. Try public Documents directory first
            File publicDir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOCUMENTS), "FT8 Plus");
            
            if (canWrite(publicDir)) {
                return publicDir;
            }
            
            // 2. Fallback to app-specific external storage (NO permissions required)
            File base = context.getExternalFilesDir(null);
            if (base == null) {
                base = context.getFilesDir(); // Fallback to internal storage
            }
            return new File(base, "FT8 Plus");
        }

        private boolean canWrite(File dir) {
            if (!dir.exists()) {
                if (!dir.mkdirs()) return false;
            }
            File testFile = new File(dir, ".write_test");
            try {
                if (testFile.createNewFile()) {
                    //noinspection ResultOfMethodCallIgnored
                    testFile.delete();
                    return true;
                }
            } catch (Exception ignored) {}
            return false;
        }

        String buildAbsPath(String name) {
            return new File(getDir(), name).getAbsolutePath();
        }

        private File getFile(String name) {
            return new File(getDir(), name);
        }

        private void ensureDir() {
            File dir = getDir();
            if (!dir.exists()) {
                dir.mkdirs();
            }
        }

        @Override
        public void ensureAllFiles(String[] names) {
            ensureDir();
            for (String n : names) {
                File f = getFile(n);
                if (!f.exists()) {
                    try {
                        //noinspection ResultOfMethodCallIgnored
                        f.createNewFile();
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        @Override
        public boolean exists(String name) {
            return getFile(name).exists();
        }

        @Override
        public String readText(String name) {
            File f = getFile(name);
            if (!f.exists()) return "";
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f),
                            StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                boolean first = true;
                while ((line = r.readLine()) != null) {
                    if (!first) sb.append('\n');
                    sb.append(line);
                    first = false;
                }
                return sb.toString();
            } catch (Exception e) {
                Log.w(TAG, "DirectFileStore read failed: " + name, e);
                return "";
            }
        }

        @Override
        public void writeText(String name, String content) {
            ensureDir();
            File f = getFile(name);
            try (FileOutputStream fos = new FileOutputStream(f, false)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.w(TAG, "DirectFileStore write failed: " + name, e);
            }
        }

        @Override
        public void appendLine(String name, String line) {
            ensureDir();
            File f = getFile(name);
            try (FileOutputStream fos = new FileOutputStream(f, true);
                 OutputStreamWriter osw = new OutputStreamWriter(fos,
                         StandardCharsets.UTF_8)) {
                osw.write(line);
                osw.write('\n');
            } catch (Exception e) {
                Log.w(TAG, "DirectFileStore append failed: " + name, e);
            }
        }

        @Override
        public void trimLines(String name, int maxLines) {
            File f = getFile(name);
            if (!f.exists()) return;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f),
                            StandardCharsets.UTF_8))) {
                List<String> lines = new ArrayList<>();
                String line;
                while ((line = r.readLine()) != null) lines.add(line);
                if (lines.size() <= maxLines) return;
                lines = lines.subList(lines.size() - maxLines, lines.size());
                try (FileOutputStream fos = new FileOutputStream(f, false);
                     OutputStreamWriter osw = new OutputStreamWriter(fos,
                             StandardCharsets.UTF_8)) {
                    for (String l : lines) {
                        osw.write(l);
                        osw.write('\n');
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "DirectFileStore trim failed: " + name, e);
            }
        }

        @Override
        public void delete(String name) {
            File f = getFile(name);
            if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }

        @Override
        public String getDisplayPath() {
            String path = getDir().getAbsolutePath();
            if (path.contains("Documents/FT8 Plus")) {
                return "PUBLIC: " + path;
            }
            return "PRIVATE: " + path;
        }
    }

    private FileStore fileStore;

    private FileStore getFileStore() {
        if (fileStore != null) return fileStore;

        // Always use a plain File-backed store. On Android 10 and
        // below this needs WRITE_EXTERNAL_STORAGE; on Android 11+
        // it needs MANAGE_EXTERNAL_STORAGE. Both are declared in
        // AndroidManifest.xml, and the Activity prompts for
        // MANAGE_EXTERNAL_STORAGE on first launch if it has not
        // been granted yet.
        //
        // The previous MediaStore-based path was unreliable on
        // MIUI, where resolver.insert() throws
        // "UNIQUE constraint failed: files._data" whenever the
        // physical file already exists, and the scan fallback did
        // not cache the returned URI, so every saveConfig() lost
        // its write.
        fileStore = new DirectFileStore(this);
        Log.i(TAG, "Persistence directory: " + fileStore.getDisplayPath());
        return fileStore;
    }

    private void cleanupLegacyDuplicates(File dir) {
        if (!dir.exists() || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            if (name.matches(".* \\(\\d+\\)\\.txt$")) {
                if (f.delete()) {
                    Log.i(TAG, "Deleted duplicate file: " + name);
                }
            }
        }
    }

    private void ensurePersistenceFiles() {
        FileStore store = getFileStore();
        store.ensureAllFiles(new String[]{
                CONFIG_FILE_NAME, CONTACT_FILE,
                HISTORY_FILE_NAME, STATUS_FILE_NAME
        });
    }

    private void loadConfigWithFallback() {
        String raw = getFileStore().readText(CONFIG_FILE_NAME);
        if (raw != null && !raw.trim().isEmpty()) {
            try {
                JSONObject json = new JSONObject(raw);
                String loadedCallsign = json.optString("callsign", "");
                if (isValidCallsignStrict(loadedCallsign)) {
                    callsign = loadedCallsign;
                } else {
                    callsign = "";
                    Log.w(TAG, "Invalid callsign in config, reset to empty");
                    saveConfig();
                }
                grid = json.optString("grid", "");
                if (!grid.isEmpty() && !isValidGrid8(grid)) grid = "";
                txFreq = json.optInt("tx_freq", 1500);
                preambleNoise = json.optBoolean("preamble_noise", false);
                Log.i(TAG, "Config loaded from " + getFileStore().getDisplayPath());
                return;
            } catch (JSONException e) {
                // The file exists but is corrupt. Do NOT overwrite it
                // with defaults: the user's callsign / grid are in
                // that file, and a transient parse failure (e.g. a
                // truncated write) must not destroy them. Leave the
                // in-memory values at their constructor defaults and
                // skip the saveConfig() call below.
                Log.e(TAG, "Failed to parse config JSON, leaving file intact", e);
                callsign = "";
                grid = "";
                return;
            }
        }

        // Empty read. Distinguish "file absent" from "file present
        // but unreadable": only the former should trigger a default
        // write, otherwise a transient MediaStore read failure
        // silently overwrites a valid config.txt.
        File f = new File(((DirectFileStore)getFileStore()).buildAbsPath(CONFIG_FILE_NAME));
        boolean physicallyExists = f.exists() && f.length() > 0;
        if (physicallyExists) {
            Log.w(TAG, "config.txt exists but read returned empty; "
                    + "keeping in-memory defaults without overwriting");
            callsign = "";
            grid = "";
            return;
        }

        callsign = "";
        grid = "";
        txFreq = 1500;
        preambleNoise = false;
        saveConfig();
    }

    private void saveConfig() {
        if (!callsign.isEmpty() && !isValidCallsignStrict(callsign)) {
            appendStatusMessage(getString(R.string.status_invalid_callsign_ignored), getCurrentUtcTime());
            Log.w(TAG, "Attempt to save invalid callsign, skipped");
            return;
        }
        JSONObject json = new JSONObject();
        try {
            json.put("callsign", callsign);
            json.put("grid", grid);
            json.put("tx_freq", txFreq);
            json.put("preamble_noise", preambleNoise);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to construct JSON", e);
            return;
        }
        getFileStore().writeText(CONFIG_FILE_NAME, json.toString());
    }

    private void updateCallsign(String newCallsign) {
        if (!isValidCallsignStrict(newCallsign)) {
            showToast(R.string.toast_invalid_callsign_format);
            return;
        }
        callsign = newCallsign;
        btnCallsign.setText(newCallsign);
        saveConfig();
        historyAdapter.notifyDataSetChanged();
    }

    // ---------- LED ----------
    private void setLedReceive() {
        runOnUiThread(() -> {
            tvLedStatus.setText("");
            tvLedStatus.setBackgroundColor(ansiColor(R.color.ansi_black));
            tvLedStatus.setTextColor(ansiColor(R.color.ansi_white));
        });
    }

    private void setLedTransmit() {
        runOnUiThread(() -> {
            tvLedStatus.setText("");
            tvLedStatus.setBackgroundColor(ansiColor(R.color.ansi_green));
            tvLedStatus.setTextColor(ansiColor(R.color.ansi_white));
        });
    }

    // ---------- Button state ----------
    private void updateButtonState() {
        runOnUiThread(() -> {
            if (isTransmitting.get()) {
                btnTransmitStop.setText(SYMBOL_STOP);
                btnTransmitStop.setBackgroundColor(ansiColor(R.color.ansi_red));
                etTxMessage.setEnabled(false);
                etTxFreq.setEnabled(false);
                chkPreambleNoise.setEnabled(false);
            } else {
                btnTransmitStop.setText(SYMBOL_TX);
                btnTransmitStop.setBackgroundColor(ansiColor(R.color.ansi_green));
                // The input field stays enabled regardless of beacon state.
                etTxMessage.setEnabled(true);
                etTxFreq.setEnabled(true);
                chkPreambleNoise.setEnabled(true);
            }
        });
    }

    // ---------- AudioTrack ----------
    private void releaseCurrentTrack() {
        if (currentAudioTrack != null) {
            if (currentAudioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                currentAudioTrack.stop();
            }
            currentAudioTrack.release();
            currentAudioTrack = null;
        }
    }

    private short[] applyFade(short[] samples) {
        if (samples == null || samples.length == 0) return samples;
        int len = samples.length;
        int fadeIn = Math.min(len, 1200);
        int fadeOut = Math.min(len, 2000);

        for (int i = 0; i < fadeIn; i++) {
            float factor = (float) i / fadeIn;
            samples[i] = (short) (samples[i] * factor);
        }
        for (int i = 0; i < fadeOut; i++) {
            float t = (float) i / fadeOut;
            float factor = (float) Math.pow(0.0001, 1.0 - t);
            int idx = len - 1 - i;
            samples[idx] = (short) (samples[idx] * factor);
        }
        return samples;
    }

    private AudioTrack createAudioTrack(short[] samples) {
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        int bufferSize = samples.length * 2;
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();
        track.write(samples, 0, samples.length);
        return track;
    }

    private short[] generateWhiteNoise(int durationMs) {
        int numSamples = (int) (durationMs / 1000.0 * SAMPLE_RATE);
        if (numSamples <= 0) return new short[0];
        short[] samples = new short[numSamples];
        Random random = new Random();
        for (int i = 0; i < numSamples; i++) {
            double amp = 0.8;
            float val = (float) (random.nextGaussian() * amp);
            if (val > 1.0f) val = 1.0f;
            if (val < -1.0f) val = -1.0f;
            samples[i] = (short) (val * 32767);
        }
        return samples;
    }

    private boolean playWhiteNoiseAndSilence(int noiseDurationMs, int silenceDurationMs) {
        if (!isTransmitting.get()) return false;
        try {
            short[] noise = generateWhiteNoise(noiseDurationMs);
            if (noise.length > 0) {
                AudioTrack track = createAudioTrack(noise);
                if (track == null) return false;
                track.play();
                int sleep = (int) (noiseDurationMs * 1.1f);
                long start = System.currentTimeMillis();
                while (isTransmitting.get() && (System.currentTimeMillis() - start) < sleep) {
                    Thread.sleep(20);
                }
                track.stop();
                track.release();
                if (!isTransmitting.get()) return false;
            }
            if (silenceDurationMs > 0) {
                long start = System.currentTimeMillis();
                while (isTransmitting.get() && (System.currentTimeMillis() - start) < silenceDurationMs) {
                    Thread.sleep(20);
                }
                if (!isTransmitting.get()) return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ---------- Free text compatibility check ----------
    private boolean isFreeTextCompatible(String text) {
        if (TextUtils.isEmpty(text)) return false;
        if (text.length() > 13) return false;
        String upper = text.toUpperCase(Locale.US);
        for (char c : upper.toCharArray()) {
            if (FREE_TEXT_CHARSET.indexOf(c) == -1) return false;
        }
        return true;
    }

    // ---------- Contact helpers ----------
    // Return the stored remark for a contact, or null when absent.
    private String lookupContactNote(String callsign) {
        if (callsign == null) return null;
        String target = callsign.trim().toUpperCase(Locale.US);
        for (Contact c : contactList) {
            if (c.callsign.equalsIgnoreCase(target)) {
                return (c.remark != null && !c.remark.isEmpty()) ? c.remark : null;
            }
        }
        return null;
    }

    private boolean contactExists(String callsign) {
        for (Contact c : contactList) {
            if (c.callsign.equals(callsign)) return true;
        }
        return false;
    }

    private static String truncateUtf8(String input, int maxBytes) {
        if (input == null) return "";
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return input;
        int len = maxBytes;
        while (len > 0 && (bytes[len] & 0xC0) == 0x80) len--;
        return new String(bytes, 0, len, StandardCharsets.UTF_8);
    }

    // =========================================================================
    // ========== TX scheduler (transparent transmission) ==========
    // =========================================================================
    private void transmitFrames(List<Frame> frames, int freq, int totalFrames, String displayText, byte[] rawData, long startSlot) {
        if (isTransmitting.get()) {
            appendStatusMessage(getString(R.string.status_tx_in_progress_ignored), getCurrentUtcTime());
            return;
        }

        final String sessionId = "tx_" + System.currentTimeMillis();
        final int txFreqLocal = freq;
        // Match the RX byte-count convention: include 0x20 padding
        // but exclude the CRC and EOT trailer bytes for continuous
        // frames. Single-frame payloads (f2=0) have no CRC/EOT.
        final int totalBytesAllWithPadding;
        if (totalFrames > 1) {
            totalBytesAllWithPadding = totalFrames * 9 - 2;
        } else {
            totalBytesAllWithPadding = rawData.length;
        }

        isTransmitting.set(true);
        receivePaused.set(false);
        updateButtonState();

        transmissionThread = new Thread(() -> {
            try {
                long firstSlot;
                if (startSlot > 0) {
                    firstSlot = startSlot;
                } else {
                    long now = getCurrentUtcTime();
                    firstSlot = ((now + 15000) / 15000) * 15000;
                }

                // Pause recording before TX (beacon is not disabled)
                if (!receivePaused.get()) {
                    stopBackgroundMemoryRx(false);
                    receivePaused.set(true);
                    runOnUiThread(this::setLedTransmit);
                }

                long waitMs = firstSlot - getCurrentUtcTime();
                if (waitMs > 0) {
                    appendStatusMessage(getString(R.string.status_tx_wait_slot, waitMs / 1000.0), firstSlot);
                    long remaining = waitMs;
                    while (remaining > 0 && isTransmitting.get()) {
                        long step = Math.min(remaining, 50);
                        Thread.sleep(step);
                        remaining -= step;
                    }
                    if (!isTransmitting.get()) {
                        appendStatusMessage(getString(R.string.status_tx_cancelled_wait), getCurrentUtcTime());
                        return;
                    }
                }
                if (!isTransmitting.get()) {
                    appendStatusMessage(getString(R.string.status_tx_cancelled_wait), getCurrentUtcTime());
                    return;
                }

                long slotStartTime = firstSlot;
                int sentCount = 0;

                appendStatusMessage(getString(R.string.status_tx_start, totalFrames), slotStartTime);

                String initialMarker = (totalFrames == 1) ? "single" : "continuous";
                updateHistoryPartial("TX", sessionId, txFreqLocal, 0, 0.0f,
                        0, totalFrames, "", "Sending", false, null, 0, slotStartTime,
                        totalFrames, totalBytesAllWithPadding, initialMarker,
                        false, null);

                for (int idx = 0; idx < totalFrames && isTransmitting.get(); idx++) {
                    long expectedTime = firstSlot + idx * 15000L;

                    if (preambleNoise) {
                        long noiseStart = expectedTime - 1000;
                        long now = getCurrentUtcTime();
                        if (now < noiseStart) {
                            long waitNoise = noiseStart - now;
                            while (waitNoise > 0 && isTransmitting.get()) {
                                long step = Math.min(waitNoise, 50);
                                Thread.sleep(step);
                                waitNoise -= step;
                            }
                        }
                        if (!playWhiteNoiseAndSilence(500, 500)) {
                            appendStatusMessage(getString(R.string.status_noise_interrupted), getCurrentUtcTime());
                            break;
                        }
                    } else {
                        long now = getCurrentUtcTime();
                        if (now < expectedTime) {
                            long waitFrame = expectedTime - now;
                            while (waitFrame > 0 && isTransmitting.get()) {
                                long step = Math.min(waitFrame, 50);
                                Thread.sleep(step);
                                waitFrame -= step;
                            }
                        }
                    }

                    if (!isTransmitting.get()) break;
                    releaseCurrentTrack();

                    Frame frame = frames.get(idx);
                    int f2 = frame.f2;
                    byte[] frameData = frame.data;
                    String hexData = bytesToHex(frameData);

                    short[] samples = FT8Encoder.encodeTransparent(f2, hexData, txFreqLocal, SAMPLE_RATE);
                    if (samples == null || samples.length == 0) {
                        throw new RuntimeException("编码失败");
                    }
                    samples = applyFade(samples);
                    short[] fullSlot = new short[SAMPLES_PER_SLOT];
                    System.arraycopy(samples, 0, fullSlot, 0, Math.min(samples.length, SAMPLES_PER_SLOT));

                    AudioTrack track = createAudioTrack(fullSlot);
                    currentAudioTrack = track;
                    track.play();

                    sentCount++;
                    // Clamp the byte count shown in the header against
                    // the padded total, but clamp the substring length
                    // against rawData.length to avoid a
                    // StringIndexOutOfBoundsException on the last frame
                    // when padding was added (e.g. 24 -> 25 bytes).
                    int bytesSoFar = Math.min(sentCount * 9, totalBytesAllWithPadding);
                    int textLen = Math.min(bytesSoFar, rawData.length);
                    String partialText = new String(rawData, 0, textLen, StandardCharsets.UTF_8);
                    updateHistoryPartial("TX", sessionId, txFreqLocal, 0, 0.0f,
                            sentCount, totalFrames, partialText, "Sending", false, null, bytesSoFar, expectedTime,
                            totalFrames, totalBytesAllWithPadding, initialMarker,
                            false, null);

                    String hexB72 = bytesToHex(frameData);
                    appendStatusMessage(getString(R.string.status_tx_log_fmt,
                            txFreqLocal, f2, sentCount, totalFrames, hexB72), expectedTime);

                    long frameEnd = expectedTime + SLOT_SECONDS * 1000L;
                    while (isTransmitting.get() && getCurrentUtcTime() < frameEnd) {
                        Thread.sleep(50);
                    }
                }

                // Wait for the current slot to end before switching back to RX
                // (matches Python tx.wait_slot_end).
                if (isTransmitting.get()) {
                    long lastFrameSlot = firstSlot + (totalFrames - 1) * 15000L;
                    long tNow = getCurrentUtcTime();
                    long waitMsEnd = lastFrameSlot + 15000L - tNow;
                    if (waitMsEnd > 100) {
                        appendStatusMessage(getString(R.string.status_tx_wait_slot_end, waitMsEnd / 1000.0), tNow);
                        long remaining = waitMsEnd;
                        while (remaining > 0 && isTransmitting.get()) {
                            long step = Math.min(remaining, 50);
                            Thread.sleep(step);
                            remaining -= step;
                        }
                    }
                }

                if (isTransmitting.get()) {
                    String finalStatus = (totalFrames == 1) ? "single" : "done";
                    updateHistoryPartial("TX", sessionId, txFreqLocal, 0, 0.0f,
                            sentCount, totalFrames, displayText, finalStatus, true, null, totalBytesAllWithPadding, slotStartTime,
                            totalFrames, totalBytesAllWithPadding, initialMarker,
                            false, null);
                    appendStatusMessage(getString(R.string.status_tx_done), getCurrentUtcTime());
                } else {
                    String partialText = new String(rawData, 0, Math.min(sentCount * 9, rawData.length), StandardCharsets.UTF_8);
                    updateHistoryPartial("TX", sessionId, txFreqLocal, 0, 0.0f,
                            sentCount, totalFrames, partialText + " " + getString(R.string.suffix_tx_interrupted),
                            "interrupted", true, null, totalBytesAllWithPadding, slotStartTime,
                            totalFrames, totalBytesAllWithPadding, initialMarker,
                            false, null);
                    appendStatusMessage(getString(R.string.status_tx_cancelled), getCurrentUtcTime());
                }

            } catch (Exception e) {
                Log.e(TAG, "Transmission exception", e);
                // Also print the full stack trace so the failing
                // frame / call site can be identified in logcat.
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        getString(R.string.toast_tx_failed, e.getMessage()),
                        Toast.LENGTH_SHORT).show());
                updateHistoryPartial("TX", sessionId, txFreqLocal, 0, 0.0f,
                        0, totalFrames, displayText + " " + getString(R.string.suffix_tx_exception),
                        "exception", true, null, 0, getCurrentUtcTime(),
                        totalFrames, totalBytesAllWithPadding, (totalFrames == 1) ? "single" : "continuous",
                        false, null);
            } finally {
                releaseCurrentTrack();
                // Resume recording
                if (receivePaused.get()) {
                    runOnUiThread(() -> {
                        setLedReceive();
                        // Restart RX regardless of beacon state so a
                        // manual one-shot TX does not permanently stop
                        // reception. isRecording.getAndSet(true) inside
                        // startBackgroundMemoryRx() guards double starts.
                        if (!isRecording.get()) {
                            startBackgroundMemoryRx();
                        }
                        receivePaused.set(false);
                    });
                }
                // Clear the input field after every transmission,
                // not just beacon TX.
                runOnUiThread(() -> {
                    etTxMessage.setText("");
                    etTxMessage.setTextColor(ansiColor(R.color.ansi_black));
                    updateInputStats();
                });
                // Flip the transmitting flag on the TX thread. It is
                // an AtomicBoolean, so this is thread-safe. Doing it
                // via runOnUiThread() used to race with a subsequent
                // TX when the UI thread was blocked, silently
                // cancelling the new transmission.
                isTransmitting.set(false);
                runOnUiThread(this::updateButtonState);
                transmissionThread = null;
            }
        });
        transmissionThread.start();
    }

    // ---------- Free text transmission ----------
    private void sendFreeText(String text, int freq, long startSlot, boolean isBeacon) {
        if (isTransmitting.get()) {
            appendStatusMessage(getString(R.string.status_tx_in_progress_ignored), getCurrentUtcTime());
            return;
        }

        String upperText = text.toUpperCase(Locale.US);
        appendStatusMessage(getString(R.string.status_free_text, upperText), getCurrentUtcTime());

        short[] samples = FT8Encoder.encodeFreeText(upperText, freq, SAMPLE_RATE);
        if (samples == null || samples.length == 0) {
            runOnUiThread(() -> Toast.makeText(this, R.string.toast_free_text_encode_failed, Toast.LENGTH_SHORT).show());
            isTransmitting.set(false);
            runOnUiThread(this::updateButtonState);
            return;
        }
        samples = applyFade(samples);
        short[] fullSlot = new short[SAMPLES_PER_SLOT];
        System.arraycopy(samples, 0, fullSlot, 0, Math.min(samples.length, SAMPLES_PER_SLOT));

        final String sessionId = "free_" + System.currentTimeMillis();
        final int txFreqLocal = freq;
        final String finalText = upperText;
        final byte[] rawData = finalText.getBytes(StandardCharsets.UTF_8);
        final boolean finalIsBeacon = isBeacon;
        // For a beacon, the payload text is the 13-char Base42 encoding;
        // the history view should show "CALL GRID" and remember the
        // grid so a tap opens the map.
        final String finalGrid;
        final String finalDisplayText;
        if (finalIsBeacon) {
            String[] loc = unpackLocationMessage(finalText);
            if (loc != null) {
                finalGrid = loc[1];
                finalDisplayText = loc[0] + " " + loc[1];
            } else {
                finalGrid = null;
                finalDisplayText = finalText;
            }
        } else {
            finalGrid = null;
            finalDisplayText = finalText;
        }

        isTransmitting.set(true);
        receivePaused.set(false);
        runOnUiThread(this::updateButtonState);

        transmissionThread = new Thread(() -> {
            boolean success = false;
            long slotTime = 0;
            try {
                long now = getCurrentUtcTime();
                long nextSlot;
                if (startSlot > 0) {
                    nextSlot = startSlot;
                } else {
                    nextSlot = ((now + 15000) / 15000) * 15000;
                }

                // Pause recording before TX (beacon is not disabled)
                if (!receivePaused.get()) {
                    stopBackgroundMemoryRx(false);
                    receivePaused.set(true);
                    runOnUiThread(this::setLedTransmit);
                }

                if (startSlot <= 0) {
                    long waitMs = nextSlot - now;
                    if (preambleNoise) {
                        long noiseStart = nextSlot - 1000;
                        now = getCurrentUtcTime();
                        if (now < noiseStart) {
                            long waitNoise = noiseStart - now;
                            while (waitNoise > 0 && isTransmitting.get()) {
                                long step = Math.min(waitNoise, 20);
                                Thread.sleep(step);
                                waitNoise -= step;
                            }
                        }
                        if (!isTransmitting.get()) {
                            appendStatusMessage(getString(R.string.status_tx_cancelled_noise), getCurrentUtcTime());
                            return;
                        }
                        if (!playWhiteNoiseAndSilence(500, 500)) {
                            appendStatusMessage(getString(R.string.status_noise_interrupted), getCurrentUtcTime());
                            return;
                        }
                    } else {
                        if (waitMs > 0) {
                            appendStatusMessage(getString(R.string.status_tx_wait_slot, waitMs / 1000.0), nextSlot);
                            long remaining = waitMs;
                            while (remaining > 0 && isTransmitting.get()) {
                                long step = Math.min(remaining, 20);
                                Thread.sleep(step);
                                remaining -= step;
                            }
                            if (!isTransmitting.get()) {
                                appendStatusMessage(getString(R.string.status_tx_cancelled_wait), getCurrentUtcTime());
                                return;
                            }
                        }
                    }
                } else {
                    // startSlot > 0: no extra wait needed
                }

                slotTime = nextSlot;
                String markerType = finalIsBeacon ? "beacon" : "free_text";
                updateHistoryPartial("TX", sessionId, txFreqLocal, 0, 0.0f,
                        0, 1, finalDisplayText, "Sending", false, null, rawData.length, slotTime,
                        1, rawData.length, markerType,
                        finalIsBeacon, finalGrid);

                releaseCurrentTrack();
                AudioTrack track = createAudioTrack(fullSlot);
                currentAudioTrack = track;
                track.play();

                long start = System.currentTimeMillis();
                while (isTransmitting.get() && (System.currentTimeMillis() - start) < SLOT_SECONDS * 1000L) {
                    Thread.sleep(50);
                }

                if (isTransmitting.get()) {
                    success = true;
                    updateHistoryPartial("TX", sessionId, txFreqLocal, 0, 0.0f,
                            1, 1, finalDisplayText, "Sent", true, null, rawData.length, slotTime,
                            1, rawData.length, markerType,
                            finalIsBeacon, finalGrid);
                    appendStatusMessage(getString(R.string.status_free_text_done), slotTime);
                } else {
                    success = false;
                    updateHistoryPartial("TX", sessionId, txFreqLocal, 0, 0.0f,
                            0, 1, finalDisplayText + " " + getString(R.string.suffix_tx_interrupted),
                            "interrupted", true, null, 0, slotTime,
                            1, rawData.length, markerType,
                            finalIsBeacon, finalGrid);
                    appendStatusMessage(getString(R.string.status_free_text_cancelled), getCurrentUtcTime());
                }

            } catch (Exception e) {
                Log.e(TAG, "Free-text transmission exception", e);
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        getString(R.string.toast_tx_error, e.getMessage()),
                        Toast.LENGTH_SHORT).show());
                updateHistoryPartial("TX", sessionId, txFreqLocal, 0, 0.0f,
                        0, 1, finalDisplayText + " " + getString(R.string.suffix_tx_fail),
                        "exception", true, null, 0, slotTime,
                        1, rawData.length, "free_text",
                        finalIsBeacon, finalGrid);
            } finally {
                releaseCurrentTrack();

                // The TX loop has already run for a full 15-second slot
                // (free text / beacon are single-frame), so it ends
                // exactly on the next slot boundary. Switching back to
                // RX here avoids wasting another full slot. User-
                // interrupt cases are handled too; slotSchedulingLoop
                // realigns itself.
                if (receivePaused.get()) {
                    runOnUiThread(() -> {
                        setLedReceive();
                        // Always resume RX. The old code had an extra
                        // "&& beaconEnabled" guard, which left the
                        // recorder stopped after a manual (non-beacon)
                        // free-text TX.
                        if (!isRecording.get()) {
                            startBackgroundMemoryRx();
                        }
                        receivePaused.set(false);
                    });
                }
                // Clear the input field after every transmission,
                // not just beacon TX. Restore the placeholder so the
                // field looks empty to the user (Python behavior).
                runOnUiThread(() -> {
                    etTxMessage.setText("");
                    etTxMessage.setTextColor(ansiColor(R.color.ansi_black));
                    updateInputStats();
                });
                runOnUiThread(() -> {
                    isTransmitting.set(false);
                    updateButtonState();
                });
                transmissionThread = null;
            }
        });
        transmissionThread.start();
    }

    private void sendFreeText(String text, int freq) {
        sendFreeText(text, freq, 0, false);
    }
    private void sendFreeText(String text, int freq, long startSlot) {
        sendFreeText(text, freq, startSlot, false);
    }

    // ---------- Start transmission ----------
    private void startFT8Transmission() {
        String rawMessage = etTxMessage.getText().toString().trim();
        
        if (TextUtils.isEmpty(rawMessage)) {
            showToast(R.string.toast_msg_empty);
            return;
        }

        if (isFreeTextCompatible(rawMessage)) {
            sendFreeText(rawMessage, txFreq);
            return;
        }

        byte[] utf8Bytes = rawMessage.getBytes(StandardCharsets.UTF_8);
        if (utf8Bytes.length > MAX_RAW_DATA_BYTES) {
            showToast(getString(R.string.toast_msg_too_long, MAX_RAW_DATA_BYTES));
            return;
        }

        List<Frame> frames = new ArrayList<>();
        String displayText = rawMessage;

        if (utf8Bytes.length <= 9) {
            // Python single-frame format: pad with 0x20 (space), no CRC/EOT.
            byte[] padded = new byte[9];
            System.arraycopy(utf8Bytes, 0, padded, 0, utf8Bytes.length);
            for (int i = utf8Bytes.length; i < 9; i++) {
                padded[i] = 0x20;
            }
            frames.add(new Frame(0, padded));
        } else {
            // Python continuous-frame layout:
            //   payload = raw + 0x20 padding to a 9-byte boundary + CRC8 + EOT.
            //   CRC8 covers raw + padding, excluding CRC and EOT.
            int minPayloadLen = utf8Bytes.length + 2;
            int totalLen = 9 * ((minPayloadLen + 8) / 9);
            int paddingLen = totalLen - utf8Bytes.length - 2;
            byte[] paddedRaw = new byte[utf8Bytes.length + paddingLen];
            System.arraycopy(utf8Bytes, 0, paddedRaw, 0, utf8Bytes.length);
            for (int i = utf8Bytes.length; i < paddedRaw.length; i++) {
                paddedRaw[i] = 0x20;
            }
            byte crc = crc8(paddedRaw);
            byte[] payload = new byte[paddedRaw.length + 2];
            System.arraycopy(paddedRaw, 0, payload, 0, paddedRaw.length);
            payload[paddedRaw.length] = crc;
            payload[paddedRaw.length + 1] = 0x04;

            for (int offset = 0; offset < payload.length; offset += 9) {
                byte[] block = new byte[9];
                System.arraycopy(payload, offset, block, 0, 9);
                frames.add(new Frame(1, block));
            }
            if (frames.size() > MAX_FRAMES) {
                showToast(getString(R.string.toast_too_many_frames, MAX_FRAMES));
                return;
            }
        }

        int totalFrames = frames.size();
        appendStatusMessage(getString(R.string.status_split_frames, totalFrames), getCurrentUtcTime());
        if (totalFrames == 1) {
            appendStatusMessage(getString(R.string.status_single_frame_mode), getCurrentUtcTime());
        } else {
            appendStatusMessage(getString(R.string.status_multi_frame_mode), getCurrentUtcTime());
            appendStatusMessage(getString(R.string.status_payload_formula), getCurrentUtcTime());
        }

        transmitFrames(frames, txFreq, totalFrames, displayText, utf8Bytes, 0);
    }

    // ---------- Stop transmission ----------
    private void stopTransmission() {
        if (!isTransmitting.get()) return;
        appendStatusMessage(getString(R.string.status_tx_cancelled), getCurrentUtcTime());
        isTransmitting.set(false);
        updateButtonState();
        releaseCurrentTrack();
        if (transmissionThread != null) {
            transmissionThread.interrupt();
            try {
                transmissionThread.join(1000);
            } catch (InterruptedException ignored) {}
            transmissionThread = null;
        }
        if (receivePaused.get()) {
            runOnUiThread(() -> {
                setLedReceive();
                // Restart RX regardless of beacon state. For a manual
                // (non-beacon) TX, beaconEnabled is false, so the old
                // condition left the recording thread and slot scheduler
                // stopped and reception died. isRecording.getAndSet(true)
                // inside startBackgroundMemoryRx() prevents double starts.
                if (!isRecording.get()) {
                    startBackgroundMemoryRx();
                }
                receivePaused.set(false);
            });
        }
        // Clear the input field after the user stops a TX.
        runOnUiThread(() -> {
            etTxMessage.setText("");
            etTxMessage.setTextColor(ansiColor(R.color.ansi_black));
            updateInputStats();
        });
    }

    // ---------- Recording ----------
    private void startBackgroundMemoryRx() {
        if (isRecording.getAndSet(true)) return;
        recordingThread = new Thread(this::continuousRecordingLoop);
        recordingThread.start();
        slotSchedulerThread = new Thread(this::slotSchedulingLoop);
        slotSchedulerThread.start();
        appendStatusMessage(getString(R.string.status_recording_started), getCurrentUtcTime());
    }

    /**
     * Stop the recording thread (optionally also stop the beacon).
     * @param stopBeacon true: also stop the beacon (full stop); false: stop only recording, leave beacon untouched
     */
    private void stopBackgroundMemoryRx(boolean stopBeacon) {
        isRecording.set(false);
        if (recordingThread != null) {
            try { recordingThread.interrupt(); recordingThread.join(1000); } catch (InterruptedException ignored) {}
            recordingThread = null;
        }
        if (slotSchedulerThread != null) {
            try { slotSchedulerThread.interrupt(); slotSchedulerThread.join(1000); } catch (InterruptedException ignored) {}
            slotSchedulerThread = null;
        }
        if (stopBeacon && beaconEnabled) {
            beaconEnabled = false;
            beaconStop.set(true);
            if (beaconThread != null) {
                try { beaconThread.interrupt(); beaconThread.join(1000); } catch (InterruptedException ignored) {}
                beaconThread = null;
            }
            runOnUiThread(() -> {
                btnBeacon.setBackgroundColor(ansiColor(R.color.ansi_black));
                btnBeacon.invalidate();
            });
        }
        appendStatusMessage(
                getString(stopBeacon ? R.string.status_recording_stopped_beacon
                                     : R.string.status_recording_stopped),
                getCurrentUtcTime());
    }

    private void stopBackgroundMemoryRx() {
        stopBackgroundMemoryRx(true);
    }

    private void continuousRecordingLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        while (isRecording.get() && !Thread.interrupted()) {
            AudioRecord audioRecord = null;
            try {
                int minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                int bufferSize = Math.max(minBufSize, 8192);
                // Use an unprocessed audio source. MediaRecorder.
                // AudioSource.MIC applies AGC, noise suppression and
                // echo cancellation on most devices, which compresses
                // the SNR of weak FT8 signals. UNPROCESSED (API 24+)
                // requests no processing at all; VOICE_RECOGNITION is
                // the fallback for older API levels and also disables
                // AGC and noise suppression.
                int audioSource;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    audioSource = MediaRecorder.AudioSource.UNPROCESSED;
                } else {
                    audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION;
                }
                audioRecord = new AudioRecord(audioSource, SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
                if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    throw new IllegalStateException("AudioRecord not initialized");
                }
                audioRecord.startRecording();
                // Surface the actual sample rate and source. If the
                // device does not support 12000 Hz natively the
                // recorder will resample internally, and the reported
                // rate differs from SAMPLE_RATE.
                Log.i(TAG, "AudioRecord started successfully"
                        + " (requested=" + SAMPLE_RATE
                        + ", actual=" + audioRecord.getSampleRate()
                        + ", source=" + audioSource + ")");
                runOnUiThread(() -> appendStatusMessage(
                        getString(R.string.status_recording_ok), getCurrentUtcTime()));

                short[] tempBuffer = new short[2048];
                while (isRecording.get() && !Thread.interrupted()) {
                    int read = audioRecord.read(tempBuffer, 0, tempBuffer.length);
                    if (read > 0) {
                        synchronized (ringBuffer) {
                            for (int i = 0; i < read; i++) {
                                ringBuffer[writePos] = tempBuffer[i];
                                writePos = (writePos + 1) % BUFFER_CAPACITY;
                                totalSamplesWritten.incrementAndGet();
                            }
                        }
                    } else if (read < 0) {
                        Log.e(TAG, "AudioRecord read error: " + read);
                        break;
                    }
                }
                break;
            } catch (Exception e) {
                Log.e(TAG, "AudioRecord loop failed", e);
                runOnUiThread(() -> appendStatusMessage(
                        getString(R.string.status_recording_restart), getCurrentUtcTime()));
                if (audioRecord != null) {
                    try { audioRecord.stop(); } catch (Exception ignore) {}
                    try { audioRecord.release(); } catch (Exception ignore) {}
                }
                try { Thread.sleep(2000); } catch (InterruptedException ignored) { break; }
            } finally {
                if (audioRecord != null) {
                    try { audioRecord.stop(); } catch (Exception ignore) {}
                    try { audioRecord.release(); } catch (Exception ignore) {}
                }
            }
        }
    }

    private void slotSchedulingLoop() {
        // Cumulative sample counter and wall-clock time observed at the
        // previous slot boundary. -1 means "uninitialised": the very
        // first boundary only records a baseline and skips the health
        // check.
        long lastSlotBoundaryTotal = -1L;
        long lastSlotBoundaryWallClock = -1L;
        // Whether the previous slot boundary reported the audio input
        // device as failed. Used to emit the failure / recovery notice
        // exactly once per state change.
        boolean audioDeviceFailed = false;
        // Timestamp of the previous decode trigger. Guards against the
        // duplicate-trigger race: if the decode executor updates
        // dtAvgSeconds while this loop is sleeping, the next
        // nextTrigger may land only 2-3 seconds after the previous
        // fire. Both the pre-sleep planned check and the post-sleep
        // actual check use this to enforce a minimum interval.
        long lastTriggerMs = 0L;
        final long MIN_TRIGGER_INTERVAL_MS = 10000L;
        while (isRecording.get() && !Thread.interrupted()) {
            long now = getCurrentUtcTime();
            // Fire the decode trigger D milliseconds AFTER each slot
            // boundary: at T_{n+1} + D the ring buffer already holds
            // the complete [T_n, T_{n+1}) audio for the slot that
            // just ended, so the unshifted 15-second window contains
            // the full signal and the decoder reports DT ~= 0.
            // The trigger-time set is {k*15000 + D : k in Z}; the
            // arithmetic below finds the smallest such value that is
            // strictly greater than now.
            long dtMs;
            synchronized (dtLock) {
                dtMs = (long)(dtAvgSeconds * 1000);
            }
            long nextTrigger = ((now - dtMs + 15000) / 15000) * 15000 + dtMs;
            if (nextTrigger <= now) {
                nextTrigger += 15000;
            }
            // Pre-sleep planned-interval guard. If dtAvg changed since
            // lastTriggerMs, the newly computed nextTrigger may be
            // less than MIN_TRIGGER_INTERVAL_MS after the previous
            // fire; push it forward to the next slot boundary.
            if (lastTriggerMs > 0
                    && nextTrigger - lastTriggerMs < MIN_TRIGGER_INTERVAL_MS) {
                nextTrigger = lastTriggerMs + 15000L;
            }
            long sleepMs = nextTrigger - now;
            if (sleepMs > 0) {
                try { Thread.sleep(sleepMs); } catch (InterruptedException e) { break; }
            }
            if (!isRecording.get()) break;
            // Post-sleep actual-interval check. Even with the planned
            // guard above, the actual wall-clock elapsed time at wake
            // can still be below MIN_TRIGGER_INTERVAL_MS when dtAvg
            // changes during the sleep. Skip this iteration in that
            // case; the next iteration will recompute a proper slot.
            if (lastTriggerMs > 0
                    && getCurrentUtcTime() - lastTriggerMs < MIN_TRIGGER_INTERVAL_MS) {
                continue;
            }

            // ---- Audio input device health check at slot start ----
            // Runs on every slot boundary even when the ring buffer
            // does not yet hold a full slot of samples. The recording
            // thread may have exited (read() returned a negative error
            // code because the mic was unplugged, taken over by
            // another app, or never available) and totalSamplesWritten
            // would then stall; deferring the check until a full slot
            // has been copied would mean it never fires in that case.
            //
            // The check compares the growth of totalSamplesWritten
            // since the previous boundary against the expected growth
            // (SAMPLE_RATE * elapsed seconds). A growth below half of
            // the expected value marks the device as failed. This
            // condition needs no samples and therefore also fires when
            // the recording thread has died.
            long currentTotalForCheck = totalSamplesWritten.get();
            long currentWallClock = System.currentTimeMillis();
            if (lastSlotBoundaryTotal >= 0 && lastSlotBoundaryWallClock > 0) {
                long delta = currentTotalForCheck - lastSlotBoundaryTotal;
                long elapsedMs = currentWallClock - lastSlotBoundaryWallClock;
                long expectedDelta = Math.max(1L, elapsedMs * SAMPLE_RATE / 1000L);
                boolean counterStalled = delta < expectedDelta / 2;
                if (counterStalled && !audioDeviceFailed) {
                    audioDeviceFailed = true;
                    appendStatusMessage(
                            getString(R.string.status_audio_device_lost),
                            getCurrentUtcTime(),
                            R.color.ansi_red);
                } else if (!counterStalled && audioDeviceFailed) {
                    audioDeviceFailed = false;
                    appendStatusMessage(
                            getString(R.string.status_audio_device_recovered),
                            getCurrentUtcTime(),
                            R.color.ansi_black);
                }
            }
            lastSlotBoundaryTotal = currentTotalForCheck;
            lastSlotBoundaryWallClock = currentWallClock;

            // Only attempt decoding when the ring buffer holds a full
            // slot of samples. Skip decoding otherwise; the health
            // check above has already run on this trigger.
            // Audio latency is compensated by the trigger time (which
            // fires D milliseconds past the slot boundary), so the
            // window itself is always the last 15 seconds of samples
            // and does not need to be shifted.
            short[] slotData = new short[SAMPLES_PER_SLOT];
            long currentTotal;
            boolean haveFullSlot = false;
            synchronized (ringBuffer) {
                currentTotal = totalSamplesWritten.get();
                if (currentTotal >= SAMPLES_PER_SLOT) {
                    long slotStartSample = currentTotal - SAMPLES_PER_SLOT;
                    if (slotStartSample < 0) slotStartSample = 0;
                    int startIdx = (int)(slotStartSample % BUFFER_CAPACITY);
                    for (int i = 0; i < SAMPLES_PER_SLOT; i++) {
                        int idx = (startIdx + i) % BUFFER_CAPACITY;
                        slotData[i] = ringBuffer[idx];
                    }
                    haveFullSlot = true;
                }
            }
            if (!haveFullSlot) continue;

            float avgAmp = getAvgAmplitude(slotData);

            // The average-amplitude check catches the "device is alive
            // but silent" case: read() keeps returning 0-filled
            // samples so the counter still grows normally, and the
            // counter-based check above does not fire. This check can
            // only run when a full slot of samples is available.
            if (!audioDeviceFailed && avgAmp < SILENT_AMP_THRESHOLD) {
                audioDeviceFailed = true;
                appendStatusMessage(
                        getString(R.string.status_audio_device_lost),
                        getCurrentUtcTime(),
                        R.color.ansi_red);
            } else if (audioDeviceFailed && avgAmp >= SILENT_AMP_THRESHOLD) {
                // Recovery from the "silent" state. The counter-based
                // check above only clears the flag when the counter
                // stalled and later resumed; a device that was silent
                // but never stalled needs this branch instead.
                audioDeviceFailed = false;
                appendStatusMessage(
                        getString(R.string.status_audio_device_recovered),
                        getCurrentUtcTime(),
                        R.color.ansi_black);
            }

            if (avgAmp >= AUDIO_VALID_THRESHOLD) {
                final short[] copy = slotData.clone();
                // The trigger fired at T_{n+1} + D, and the 15-second
                // window covers [T_n, T_{n+1}) = [T_{n+1} - 15s, T_{n+1}),
                // so the wall-clock start of the decoded slot is
                // nextTrigger - D - 15000.
                final long slotBoundary = Math.round(nextTrigger / 15000.0) * 15000L;
                final long slotUtc = slotBoundary - 15000L;
                decodeExecutor.execute(() -> {
                    long start = System.currentTimeMillis();
                    String decoded = FT8Decoder.decodeFt8(copy, SAMPLE_RATE);
                    long cost = System.currentTimeMillis() - start;
                    Log.d(TAG, "decode cost: " + cost + " ms");
                    if (decoded != null && !decoded.isEmpty()) {
                        String cleaned = cleanDecodedString(decoded);
                        if (!cleaned.isEmpty()) {
                            processDecodedMessages(slotUtc, cleaned);
                        }
                    } else {
                        Log.w(TAG, "no messages");
                    }
                });
            } else {
                Log.w(TAG, "weak signal, avgAmp=" + avgAmp);
            }

            // Record the trigger so the next iteration can enforce the
            // minimum-interval guard. This runs even when the slot was
            // skipped for weak signal, because the trigger itself did
            // happen at this moment.
            lastTriggerMs = nextTrigger;
        }
    }

    private float getAvgAmplitude(short[] buffer) {
        long sum = 0;
        for (short s : buffer) sum += Math.abs(s);
        return (float) sum / buffer.length;
    }

    private String cleanDecodedString(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : raw.toCharArray()) {
            if (c == '\n' || c == '\r' || (c >= 0x20 && c <= 0x7E) || (c >= 0x4E00 && c <= 0x9FFF)) {
                sb.append(c);
            } else if (c == '\t') {
                sb.append(' ');
            }
        }
        return sb.toString().trim();
    }

    // ---------- RX-side decoded-message processing ----------
    private void processDecodedMessages(long slotUtc, String decodedMessages) {
        String[] lines = decodedMessages.split("\n");
        for (String line : lines) {
            if (TextUtils.isEmpty(line.trim())) continue;
            appendStatusMessage("[RX] " + line, slotUtc);

            // Feed the decoded message's DT into the EMA. Bounds keep
            // a single bogus decode from skewing the estimate. The
            // EMA is read by slotSchedulingLoop on the next slot
            // boundary to compensate the audio input latency.
            // Two update rules:
            //   - Normal: weighted EMA with alpha 0.5.
            //   - Jump: if |lineDt - avg| exceeds
            //     DT_JUMP_THRESHOLD_SECONDS, snap directly to lineDt
            //     so a real latency change is picked up on the next
            //     slot instead of being smoothed away over many slots.
            float lineDt = extractDt(line);
            if (lineDt >= DT_MIN_SECONDS && lineDt <= DT_MAX_SECONDS) {
                synchronized (dtLock) {
                    if (!dtAvgInitialized) {
                        dtAvgSeconds = lineDt;
                        dtAvgInitialized = true;
                    } else if (Math.abs(lineDt - dtAvgSeconds)
                            > DT_JUMP_THRESHOLD_SECONDS) {
                        // Hard snap: the measured latency changed by
                        // more than one second. Trust this reading.
                        Log.d(TAG, "DT jump: line=" + lineDt
                                + " prev_avg=" + dtAvgSeconds);
                        dtAvgSeconds = lineDt;
                    } else {
                        dtAvgSeconds = dtAvgSeconds * (1.0 - DT_EMA_ALPHA)
                                + lineDt * DT_EMA_ALPHA;
                    }
                }
                Log.d(TAG, "DT tracking: line=" + lineDt
                        + " avg=" + dtAvgSeconds);
            }

            if (line.contains("i3:0")) {
                String text = extractFreeTextFromLine(line);
                if (!TextUtils.isEmpty(text)) {
                    int freq = extractFreq(line);
                    int snr = extractSnr(line);
                    float dt = extractDt(line);

                    boolean isBeacon = false;
                    String detectedGrid = null;
                    String displayText = text;
                    String[] loc = unpackLocationMessage(text.trim());
                    if (loc != null) {
                        isBeacon = true;
                        detectedGrid = loc[1];
                        displayText = loc[0] + " " + loc[1];
                    }

                    String sessionId = "free_" + System.currentTimeMillis();
                    String markerType = isBeacon ? "beacon" : "free_text";
                    updateHistoryPartial("RX", sessionId, freq, snr, dt,
                            1, 1, displayText, null, true, null,
                            displayText.getBytes().length, slotUtc,
                            1, displayText.getBytes().length, markerType,
                            isBeacon, detectedGrid);
                }
                continue;
            }

            if (line.contains("i3:1")) {
                continue;
            }

            if (line.contains("i3:6") || line.contains("i3=6")) {
                int f2 = extractF2(line);
                int freq = extractFreq(line);
                int snr = extractSnr(line);
                float dt = extractDt(line);
                byte[] frameBytes = extractB72Bytes(line);
                if (frameBytes == null) continue;

                if (f2 == 0) {
                    // Python uses rstrip(b' '): drop trailing 0x20 padding.
                    int len = frameBytes.length;
                    while (len > 0 && frameBytes[len - 1] == 0x20) len--;
                    String text = new String(frameBytes, 0, len, StandardCharsets.UTF_8).trim();
                    if (text.isEmpty()) text = getString(R.string.status_rx_empty_msg);
                    String sessionId = "rx_single_" + System.currentTimeMillis();
                    updateHistoryPartial("RX", sessionId, freq, snr, dt,
                            1, 1, text, null, true, null, len, slotUtc,
                            1, len, "single",
                            false, null);
                    appendStatusMessage(getString(R.string.status_rx_single_done), getCurrentUtcTime());
                } else if (f2 == 1) {
                    synchronized (transparentBuffers) {
                        TransparentBuffer buffer = null;
                        for (TransparentBuffer buf : transparentBuffers.values()) {
                            if (!buf.isComplete && Math.abs(buf.firstFreq - freq) <= FREQ_GROUP_TOLERANCE) {
                                buffer = buf;
                                break;
                            }
                        }
                        if (buffer == null) {
                            buffer = new TransparentBuffer(nextSessionId++, freq, snr, dt, slotUtc);
                            transparentBuffers.put(buffer.historySessionId, buffer);
                            appendStatusMessage(getString(R.string.status_rx_new_session, freq),
                                    getCurrentUtcTime());
                        }
                        // Pass the 15-second slot index so the buffer can
                        // detect missing frames between arrivals.
                        buffer.appendBlock(frameBytes, slotUtc / 15000L);
                    }
                }
            }
        }
    }

    private String extractFreeTextFromLine(String line) {
        Pattern p = Pattern.compile("i3:0\\s+\\d+Hz\\s+(.*)$");
        Matcher m = p.matcher(line);
        if (m.find()) {
            String text = m.group(1).trim();
            if (!TextUtils.isEmpty(text)) return text;
        }
        int lastSpace = line.lastIndexOf(' ');
        if (lastSpace > 0 && lastSpace < line.length() - 1) {
            return line.substring(lastSpace + 1).trim();
        }
        return null;
    }

    // ---------- Status log writing ----------
    // Display a Toast and route the same text into the status log,
    // matching Python _on_toast -> _insert_log. The (int) overload
    // forwards to the (String) overload exactly once; the (String)
    // overload performs the Toast + appendStatusMessage.
    private void showToast(int resId) {
        showToast(getString(resId));
    }

    private void showToast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        appendStatusMessage(msg);
    }

    private void appendStatusMessage(String msg) {
        appendStatusMessage(msg, getCurrentUtcTime());
    }

    private void appendStatusMessage(String msg, long utcTimestamp) {
        appendStatusMessage(msg, utcTimestamp, 0);
    }

    private void appendStatusMessage(String msg, long utcTimestamp, int colorRes) {
        // Match Python _insert_log: only prepend a timestamp when
        // the message does not already start with one. The
        // "[20" prefix covers every yyyy-MM-dd timestamp and
        // leaves "[RX]" / "[TX]" prefixes timestampable.
        String msgWithTime;
        if (msg != null && msg.startsWith("[20")) {
            msgWithTime = msg;
        } else {
            String timestamp = utcFullFormat.format(new Date(utcTimestamp));
            msgWithTime = "[" + timestamp + "] " + msg;
        }
        final String line = msgWithTime;
        // colorRes == 0 means "default" (black). Callers that need a
        // different color pass a color resource id explicitly.
        final int colorInt = (colorRes == 0)
                ? ansiColor(R.color.ansi_black)
                : ansiColor(colorRes);

        // Only the widget update runs on the UI thread. Disk I/O
        // is scheduled on logWriter below.
        runOnUiThread(() -> {
            // Defensive guard: appendStatusMessage() can be invoked
            // from onRequestPermissionsResult() before the views exist,
            // or during an Activity relaunch. In those cases skip the
            // UI update; the log file still gets the line.
            if (tvStatus == null || scrollView == null) {
                return;
            }
            // Reuse the current SpannableStringBuilder when possible so
            // per-line colors survive across appends. Fall back to a
            // fresh builder only when the TextView currently holds
            // plain text (e.g. the very first append right after a
            // loadStatusFromFile() that used a String).
            CharSequence current = tvStatus.getText();
            SpannableStringBuilder sb = (current instanceof SpannableStringBuilder)
                    ? (SpannableStringBuilder) current
                    : new SpannableStringBuilder(current == null ? "" : current);

            // Drop the initial "Waiting for sync..." placeholder on
            // the first real log line.
            if (sb.length() == 0
                    || sb.toString().equals(getString(R.string.status_waiting))) {
                sb.clear();
            } else {
                sb.append('\n');
            }

            int start = sb.length();
            sb.append(line);
            sb.setSpan(new ForegroundColorSpan(colorInt),
                    start, sb.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

            // Trim to the most recent MAX_STATUS_LINES lines by
            // deleting whole head lines from the existing buffer.
            // Use MAX_STATUS_LINES instead of a hardcoded 100 so
            // the widget limit stays in sync with the constant.
            // Delete a whole batch of head lines in a single
            // delete() instead of looping one line at a time; the
            // previous version re-scanned sb from the start for
            // every removed line, which is O(n*k) and caused
            // visible main-thread jank (Choreographer
            // "Skipped N frames") once the status area filled up.
            int newlineCount = 0;
            for (int i = 0; i < sb.length(); i++) {
                if (sb.charAt(i) == '\n') newlineCount++;
            }
            if (newlineCount + 1 > MAX_STATUS_LINES + STATUS_TRIM_BATCH) {
                int toRemove = newlineCount + 1 - MAX_STATUS_LINES;
                int idx = 0;
                int removed = 0;
                while (removed < toRemove && idx < sb.length()) {
                    if (sb.charAt(idx) == '\n') removed++;
                    idx++;
                }
                sb.delete(0, idx);
            }

            tvStatus.setText(sb, TextView.BufferType.SPANNABLE);
            scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
        });

        logWriter.execute(() -> appendToStore(STATUS_FILE_NAME, line));
    }

    // ---------- History log persistence ----------
    private void saveHistoryToFile(HistoryEntry entry) {
        JSONObject json = new JSONObject();
        try {
            json.put("ts", utcFullFormat.format(new Date(entry.timestamp)));
            json.put("dir", entry.direction);
            json.put("freq", entry.freq);
            json.put("text", entry.text);
            json.put("marker", entry.markerType);
            json.put("partial", !entry.isComplete);
            json.put("crc", entry.crcStatus);
            json.put("beacon", entry.isBeacon);
            json.put("grid", entry.grid6);
            json.put("src", entry.sourceCallsign);
            json.put("tgt", entry.targetCallsign);
            // Contact remark, aligned with the Python history format.
            json.put("note", entry.contactNote);
            json.put("frames", entry.framesReceived);
            json.put("total", entry.totalFramesAll > 0 ? entry.totalFramesAll : entry.totalFrames);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to construct history JSON", e);
            return;
        }
        appendToStore(HISTORY_FILE_NAME, json.toString());
    }

    private void appendToStore(String name, String line) {
        FileStore store = getFileStore();
        store.appendLine(name, line);
        store.trimLines(name, MAX_LOG_LINES);
    }

    // ---------- Load status log ----------
    private void loadStatusFromFile() {
        String content = getFileStore().readText(STATUS_FILE_NAME);
        if (content == null || content.isEmpty()) return;
        runOnUiThread(() -> {
            tvStatus.setText(content);
            scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    // ---------- Load history log ----------
    private void loadHistoryFromFile() {
        String content = getFileStore().readText(HISTORY_FILE_NAME);
        if (content == null || content.isEmpty()) return;
        String[] allLines = content.split("\n", -1);
        int count = 0;
        int start = Math.max(0, allLines.length - MAX_LOG_LINES);
        for (int idx = start; idx < allLines.length; idx++) {
            String line = allLines[idx];
            if (TextUtils.isEmpty(line)) continue;
            try {
                JSONObject json = new JSONObject(line);
                long ts = 0;
                String tsStr = json.optString("ts", null);
                if (tsStr != null) {
                    try {
                        ts = utcFullFormat.parse(tsStr).getTime();
                    } catch (java.text.ParseException e) {
                        ts = System.currentTimeMillis();
                    }
                }
                String dir = json.optString("dir", "RX");
                int freq = json.optInt("freq", 0);
                String text = json.optString("text", "");
                String marker = json.optString("marker", "");
                boolean partial = json.optBoolean("partial", false);
                String crc = json.optString("crc", null);
                boolean beacon = json.optBoolean("beacon", false);
                String grid = json.optString("grid", null);
                String src = json.optString("src", null);
                String tgt = json.optString("tgt", null);
                String note = json.optString("note", null);
                int frames = json.optInt("frames", 0);
                int total = json.optInt("total", 1);

                String sessionId = "load_" + System.currentTimeMillis() + "_" + (++count);

                HistoryEntry entry = new HistoryEntry(
                        dir, ts, freq, 0, 0.0f,
                        frames, total, text,
                        null, !partial, sessionId, crc,
                        text.getBytes(StandardCharsets.UTF_8).length,
                        total, text.getBytes(StandardCharsets.UTF_8).length,
                        marker, beacon, grid
                );
                entry.sourceCallsign = src;
                entry.targetCallsign = tgt;
                entry.contactNote = note;

                runOnUiThread(() -> historyAdapter.updateOrAdd(entry));
            } catch (JSONException e) {
                Log.w(TAG, "Skipped invalid history line: " + line);
            }
        }
        Log.i(TAG, "History log loaded, total " + count + " entries");
    }

    // ---------- Contact management ----------
    private void loadContacts() {
        contactList.clear();
        String raw = getFileStore().readText(CONTACT_FILE);
        if (raw == null || raw.isEmpty()) return;
        for (String line : raw.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split(",", 2);
            if (parts.length == 2) {
                contactList.add(new Contact(parts[0].trim(), parts[1].trim()));
            }
        }
        if (contactList.size() > MAX_CONTACTS) {
            contactList = contactList.subList(0, MAX_CONTACTS);
        }
    }

    private void saveContacts() {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Contact c : contactList) {
            if (count++ >= MAX_CONTACTS) break;
            sb.append(c.callsign).append(',').append(c.remark).append('\n');
        }
        getFileStore().writeText(CONTACT_FILE, sb.toString());
    }


    private void showHistoryOptionsDialog() {
        String[] items = {
                getString(R.string.dlg_history_copy_all),
                getString(R.string.dlg_history_clear)
        };
        new AlertDialog.Builder(MainActivity.this)
                .setTitle(R.string.dlg_history_title)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            StringBuilder sb = new StringBuilder();
                            for (HistoryEntry entry : historyList) {
                                sb.append(entry.getDisplayText(MainActivity.this, callsign)).append("\n\n");
                            }
                            String fullText = sb.toString().trim();
                            if (!TextUtils.isEmpty(fullText)) {
                                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                                ClipData clip = ClipData.newPlainText("FT8 History", fullText);
                                clipboard.setPrimaryClip(clip);
                                showToast(R.string.toast_history_copied);
                            } else {
                                showToast(R.string.toast_history_empty);
                            }
                            break;
                        case 1:
                            historyList.clear();
                            historyIndexMap.clear();
                            sendSessionFirstTimestamp.clear();
                            historyAdapter.notifyDataSetChanged();
                            getFileStore().delete(HISTORY_FILE_NAME);
                            getFileStore().ensureAllFiles(new String[]{HISTORY_FILE_NAME});
                            showToast(R.string.toast_history_cleared);
                            break;
                    }
                })
                .setNegativeButton(R.string.dlg_cancel, null)
                .show();
    }

    // Contacts dialog
    private void showContactsDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(16, 16, 16, 16);

        ListView listView = new ListView(this);
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        listView.setLayoutParams(listParams);

        List<String> displayList = new ArrayList<>();
        displayList.add(getString(R.string.dlg_contacts_all));
        for (Contact c : contactList) {
            displayList.add(c.remark.isEmpty() ? c.callsign : c.callsign + ", " + c.remark);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_activated_1, displayList);
        listView.setAdapter(adapter);

        LinearLayout btnLayout = new LinearLayout(this);
        btnLayout.setOrientation(LinearLayout.HORIZONTAL);
        btnLayout.setPadding(0, 16, 0, 0);
        Button btnNew = new Button(this);
        btnNew.setText(R.string.dlg_new);
        Button btnEdit = new Button(this);
        btnEdit.setText(R.string.dlg_edit);
        Button btnDelete = new Button(this);
        btnDelete.setText(R.string.dlg_delete);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        btnParams.setMargins(4, 0, 4, 0);
        btnNew.setLayoutParams(btnParams);
        btnEdit.setLayoutParams(btnParams);
        btnDelete.setLayoutParams(btnParams);
        btnLayout.addView(btnNew);
        btnLayout.addView(btnEdit);
        btnLayout.addView(btnDelete);

        layout.addView(listView);
        layout.addView(btnLayout);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.dlg_contacts_title);
        builder.setView(layout);
        AlertDialog dialog = builder.create();

        final long[] lastClickTime = {0};

        listView.setOnItemClickListener((parent, view, position, id) -> {
            long now = System.currentTimeMillis();
            if (now - lastClickTime[0] < 500) {
                dialog.dismiss();
                if (position == 0) {
                    fillInputWithCallsignOnly();
                } else {
                    Contact contact = contactList.get(position - 1);
                    fillInputWithContact(contact);
                }
            }
            lastClickTime[0] = now;
        });

        btnNew.setOnClickListener(v -> {
            showAddContactDialog();
            refreshContactList(displayList, adapter);
        });

        btnEdit.setOnClickListener(v -> {
            int pos = listView.getCheckedItemPosition();
            if (pos <= 0) {
                showToast(R.string.toast_select_contact_first);
                return;
            }
            final int index = pos - 1;
            if (index < 0 || index >= contactList.size()) {
                showToast(R.string.toast_invalid_selection);
                return;
            }
            showEditContactDialog(index);
            refreshContactList(displayList, adapter);
        });

        btnDelete.setOnClickListener(v -> {
            int pos = listView.getCheckedItemPosition();
            if (pos <= 0) {
                showToast(R.string.toast_select_contact_first);
                return;
            }
            final int index = pos - 1;
            if (index < 0 || index >= contactList.size()) {
                showToast(R.string.toast_invalid_selection);
                return;
            }
            final Contact contact = contactList.get(index);
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle(R.string.dlg_contacts_delete_title)
                    .setMessage(getString(R.string.dlg_contacts_delete_msg, contact.callsign))
                    .setPositiveButton(R.string.dlg_ok, (d, which) -> {
                        contactList.remove(index);
                        saveContacts();
                        refreshContactList(displayList, adapter);
                        showToast(R.string.toast_deleted);
                    })
                    .setNegativeButton(R.string.dlg_cancel, null)
                    .show();
        });

        dialog.show();
    }

    private void refreshContactList(List<String> displayList, ArrayAdapter<String> adapter) {
        displayList.clear();
        displayList.add(getString(R.string.dlg_contacts_all));
        for (Contact c : contactList) {
            displayList.add(c.remark.isEmpty() ? c.callsign : c.callsign + ", " + c.remark);
        }
        adapter.notifyDataSetChanged();
    }

    private void showAddContactDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.dlg_contacts_add_title);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 20);

        final EditText etCallsign = new EditText(this);
        etCallsign.setHint(R.string.dlg_hint_callsign);
        layout.addView(etCallsign);

        final EditText etRemark = new EditText(this);
        etRemark.setHint(R.string.dlg_hint_remark);
        layout.addView(etRemark);

        builder.setView(layout);

        builder.setPositiveButton(R.string.dlg_save, (dialog, which) -> {
            String call = etCallsign.getText().toString().trim().toUpperCase(Locale.US);
            String remark = etRemark.getText().toString().trim();
            if (call.isEmpty()) {
                showToast(R.string.toast_callsign_empty);
                return;
            }
            if (!isValidCallsignStrict(call)) {
                showToast(R.string.toast_invalid_callsign_format);
                return;
            }
            if (contactList.size() >= MAX_CONTACTS) {
                showToast(getString(R.string.toast_contacts_limit, MAX_CONTACTS));
                return;
            }
            if (contactExists(call)) {
                showToast(R.string.toast_callsign_exists);
                return;
            }
            String truncatedRemark = truncateUtf8(remark, MAX_NOTE_BYTES);
            contactList.add(new Contact(call, truncatedRemark));
            saveContacts();
            showToast(R.string.toast_added);
        });
        builder.setNegativeButton(R.string.dlg_cancel, null);
        builder.show();
    }

    private void showEditContactDialog(final int index) {
        final Contact contact = contactList.get(index);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.dlg_contacts_edit_title);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 20);

        final EditText etCallsign = new EditText(this);
        etCallsign.setHint(R.string.dlg_hint_callsign);
        etCallsign.setText(contact.callsign);
        layout.addView(etCallsign);

        final EditText etRemark = new EditText(this);
        etRemark.setHint(R.string.dlg_hint_remark);
        etRemark.setText(contact.remark);
        layout.addView(etRemark);

        builder.setView(layout);

        builder.setPositiveButton(R.string.dlg_save, (dialog, which) -> {
            String call = etCallsign.getText().toString().trim().toUpperCase(Locale.US);
            String remark = etRemark.getText().toString().trim();
            if (call.isEmpty()) {
                showToast(R.string.toast_callsign_empty);
                return;
            }
            if (!isValidCallsignStrict(call)) {
                showToast(R.string.toast_invalid_callsign_format);
                return;
            }
            for (int i = 0; i < contactList.size(); i++) {
                if (i != index && contactList.get(i).callsign.equals(call)) {
                    showToast(R.string.toast_callsign_exists);
                    return;
                }
            }
            String truncatedRemark = truncateUtf8(remark, MAX_NOTE_BYTES);
            contactList.set(index, new Contact(call, truncatedRemark));
            saveContacts();
            showToast(R.string.toast_updated);
        });
        builder.setNegativeButton(R.string.dlg_cancel, null);
        builder.show();
    }

    private void fillInputWithCallsignOnly() {
        if (!isValidCallsignStrict(callsign)) {
            showToast(R.string.toast_fix_callsign_first);
            return;
        }
        String text = callsign + " ";
        etTxMessage.setText(text);
        etTxMessage.setSelection(text.length());
        updateInputStats();
    }

    private void fillInputWithContact(Contact contact) {
        if (!isValidCallsignStrict(callsign)) {
            showToast(R.string.toast_fix_callsign_first);
            return;
        }
        String text = callsign + " " + contact.callsign + " ";
        etTxMessage.setText(text);
        etTxMessage.setSelection(text.length());
        updateInputStats();
    }

    // ================================================================
    // Location: fetch the system location, convert lat/lon to an
    // 8-character Maidenhead grid, and fill the TX input with
    // "<CALL> <GRID8>". Also recognizes incoming "CALL GRID8" location
    // messages and lets the user tap such an entry to open the map.
    // ================================================================
    // Convert lat/lon (degrees) to an 8-character Maidenhead grid.
    // Mirrors the Python reference implementation exactly, including
    // the use of lowercase letters for characters 5 and 6.
    private static String latLonToGrid8(double lat, double lon) {
        double lonA = lon + 180.0;
        double latA = lat + 90.0;

        int fLon = (int) (lonA / 20.0);
        int fLat = (int) (latA / 10.0);
        lonA -= fLon * 20.0;
        latA -= fLat * 10.0;

        int sLon = (int) (lonA / 2.0);
        int sLat = (int) (latA / 1.0);
        lonA -= sLon * 2.0;
        latA -= sLat * 1.0;

        int subLon = (int) (lonA * 12.0);
        int subLat = (int) (latA * 24.0);
        lonA -= subLon * (1.0 / 12.0);
        latA -= subLat * (1.0 / 24.0);

        int extLon = (int) (lonA * 120.0);
        int extLat = (int) (latA * 240.0);

        return "" + (char) ('A' + fLon) + (char) ('A' + fLat)
                + sLon + sLat
                + (char) ('a' + subLon) + (char) ('a' + subLat)
                + extLon + extLat;
    }

    // Convert an 8-character Maidenhead grid to the center (lat, lon).
    private static double[] grid8ToLatLon(String grid) {
        if (grid == null || grid.length() != 8) {
            throw new IllegalArgumentException("Grid must be exactly 8 characters long");
        }
        String g = grid.toUpperCase(Locale.US);
        double lon = (g.charAt(0) - 'A') * 20.0 - 180.0;
        double lat = (g.charAt(1) - 'A') * 10.0 - 90.0;
        lon += Character.getNumericValue(g.charAt(2)) * 2.0;
        lat += Character.getNumericValue(g.charAt(3)) * 1.0;
        lon += (Character.toLowerCase(g.charAt(4)) - 'a') * (1.0 / 12.0);
        lat += (Character.toLowerCase(g.charAt(5)) - 'a') * (1.0 / 24.0);
        lon += Character.getNumericValue(g.charAt(6)) * (1.0 / 120.0);
        lat += Character.getNumericValue(g.charAt(7)) * (1.0 / 240.0);
        lon += 1.0 / 240.0;
        lat += 1.0 / 480.0;
        return new double[]{lat, lon};
    }

    private static boolean isValidGrid8(String text) {
        if (text == null) return false;
        return text.toUpperCase(Locale.US).matches("[A-R]{2}[0-9]{2}[A-X]{2}[0-9]{2}");
    }


    // ================================================================
    // Location message codec
    //   C2 (2 bits, fixed 00) + C37 (37-bit callsign, base 36)
    //   + G31 (31-bit strict 8-char Maidenhead grid) = 70 bits,
    //   encoded as 13 Base42 chars (space at index 41).
    // ================================================================
    private static final String LOC_B42_CHARS =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ+-./? ";
    private static final int LOC_B42_LEN = 13;

    private static Long encodeCallsign36(String call) {
        if (call == null) return null;
        String c = call.trim().toUpperCase(Locale.US);
        if (c.isEmpty() || c.length() > 7) return null;
        long v = 0;
        for (int i = 0; i < c.length(); i++) {
            char ch = c.charAt(i);
            int d;
            if (ch >= '0' && ch <= '9') d = ch - '0';
            else if (ch >= 'A' && ch <= 'Z') d = ch - 'A' + 10;
            else return null;
            v = v * 36 + d;
            if (v >= (1L << 37)) return null;
        }
        return v;
    }

    private static String decodeCallsign36(long v) {
        if (v < 0 || v >= (1L << 37)) return null;
        if (v == 0) return "";
        StringBuilder sb = new StringBuilder();
        while (v > 0) {
            int d = (int) (v % 36);
            v /= 36;
            sb.append(d < 10 ? (char) ('0' + d) : (char) ('A' + d - 10));
        }
        return sb.reverse().toString();
    }

    private static Long encodeGrid31(String grid) {
        if (!isValidGrid8(grid)) return null;
        String g = grid.trim().toUpperCase(Locale.US);
        long v = g.charAt(0) - 'A';
        v = v * 18 + (g.charAt(1) - 'A');
        v = v * 10 + (g.charAt(2) - '0');
        v = v * 10 + (g.charAt(3) - '0');
        v = v * 24 + (g.charAt(4) - 'A');
        v = v * 24 + (g.charAt(5) - 'A');
        v = v * 10 + (g.charAt(6) - '0');
        v = v * 10 + (g.charAt(7) - '0');
        if (v >= (1L << 31)) return null;
        return v;
    }

    private static String decodeGrid31(long v) {
        if (v < 0 || v >= (1L << 31)) return null;
        int u2 = (int) (v % 10); v /= 10;
        int u1 = (int) (v % 10); v /= 10;
        int t2 = (int) (v % 24); v /= 24;
        int t1 = (int) (v % 24); v /= 24;
        int s2 = (int) (v % 10); v /= 10;
        int s1 = (int) (v % 10); v /= 10;
        int f2 = (int) (v % 18); v /= 18;
        int f1 = (int) (v % 18); v /= 18;
        if (v != 0) return null;
        // Match latLonToGrid8's mixed case (lowercase chars 5 and 6).
        return "" + (char) ('A' + f1) + (char) ('A' + f2)
                + s1 + s2
                + (char) ('a' + t1) + (char) ('a' + t2)
                + u1 + u2;
    }

    // BigInteger constants for the 70-bit location payload.
    // A 13-character Base42 string represents up to 42^13 values
    // (~70 bits), which exceeds Java's 64-bit long. The 68-bit
    // payload (C2=0) must therefore be handled with BigInteger.
    private static final BigInteger LOC_B42_BASE = BigInteger.valueOf(42);
    private static final BigInteger LOC_B42_MAX = LOC_B42_BASE.pow(LOC_B42_LEN);
    private static final BigInteger LOC_V_MAX = BigInteger.ONE.shiftLeft(68);
    private static final BigInteger LOC_C37_MASK =
            BigInteger.ONE.shiftLeft(37).subtract(BigInteger.ONE);
    private static final BigInteger LOC_G31_MASK =
            BigInteger.ONE.shiftLeft(31).subtract(BigInteger.ONE);

    private static String encodeBase42_13(BigInteger v) {
        if (v == null || v.signum() < 0 || v.compareTo(LOC_B42_MAX) >= 0) {
            return null;
        }
        char[] out = new char[LOC_B42_LEN];
        BigInteger base = LOC_B42_BASE;
        for (int i = LOC_B42_LEN - 1; i >= 0; i--) {
            BigInteger[] qr = v.divideAndRemainder(base);
            out[i] = LOC_B42_CHARS.charAt(qr[1].intValue());
            v = qr[0];
        }
        return new String(out);
    }

    private static BigInteger decodeBase42_13(String s) {
        if (s == null || s.length() != LOC_B42_LEN) return null;
        BigInteger v = BigInteger.ZERO;
        for (int i = 0; i < LOC_B42_LEN; i++) {
            int idx = LOC_B42_CHARS.indexOf(s.charAt(i));
            if (idx < 0) return null;
            v = v.multiply(LOC_B42_BASE).add(BigInteger.valueOf(idx));
        }
        return v;
    }

    // Pack (callsign, 8-char grid) into a 13-char Base42 string.
    // Returns null when either input is invalid.
    private static String packLocationMessage(String call, String grid) {
        Long c37 = encodeCallsign36(call);
        if (c37 == null) {
            Log.w(TAG, "packLocationMessage: encodeCallsign36 failed for '"
                    + call + "'");
            return null;
        }
        Long g31 = encodeGrid31(grid);
        if (g31 == null) {
            Log.w(TAG, "packLocationMessage: encodeGrid31 failed for '"
                    + grid + "'");
            return null;
        }
        BigInteger v = BigInteger.valueOf(c37).shiftLeft(31)
                .or(BigInteger.valueOf(g31));
        String out = encodeBase42_13(v);
        if (out == null) {
            Log.w(TAG, "packLocationMessage: encodeBase42_13 failed, v=" + v);
        }
        return out;
    }

    // Inverse of packLocationMessage.
    // Returns {call, grid} or null. Validates v < 2^68 (C2 == 0) and
    // requires the decoded callsign and grid to pass structural checks.
    private static String[] unpackLocationMessage(String text) {
        if (text == null) return null;
        String s = text.trim();
        if (s.length() < LOC_B42_LEN) {
            StringBuilder sb = new StringBuilder();
            for (int i = s.length(); i < LOC_B42_LEN; i++) sb.append('0');
            sb.append(s);
            s = sb.toString();
        }
        if (s.length() != LOC_B42_LEN) return null;
        BigInteger v = decodeBase42_13(s);
        if (v == null || v.compareTo(LOC_V_MAX) >= 0) return null;
        long c37 = v.shiftRight(31).and(LOC_C37_MASK).longValue();
        long g31 = v.and(LOC_G31_MASK).longValue();
        String call = decodeCallsign36(c37);
        String grid = decodeGrid31(g31);
        if (call == null || grid == null) return null;
        if (!isValidCallsignStrict(call)) return null;
        if (!isValidGrid8(grid)) return null;
        return new String[]{call, grid};
    }

    // Open a grid8 on the system map; fall back to Bing Maps if needed.
    //
    // The label argument is the beacon callsign and is carried in
    // both the geo: URI (as the ?q=...) label and the Bing Maps pin
    // label, so the marker shows the station rather than a generic
    // "MyPoint" placeholder.
    //
    // The geo: URI form is the standard Android "map point" URI
    // accepted by Google Maps, Amap, Baidu Maps and most other map
    // applications:
    //
    //     geo:<lat>,<lon>?q=<lat>,<lon>(<label>)
    //
    // The previous version omitted the label, which made some
    // applications (Amap on MIUI in particular) open without a pin
    // and appear to do nothing, even though the intent was
    // delivered.
    private void openGridOnMap(String grid, String label) {
        double[] latLon;
        try {
            latLon = grid8ToLatLon(grid);
        } catch (Exception e) {
            showToast(R.string.toast_location_invalid);
            return;
        }
        double lat = latLon[0];
        double lon = latLon[1];

        String displayLabel = (label == null || label.trim().isEmpty())
                ? "MyPoint" : label.trim();
        // Uri.encode leaves A-Z0-9 and a few punctuation marks
        // untouched, which is exactly what a callsign needs.
        String encodedLabel = Uri.encode(displayLabel);

        // 1. System default map application via a labelled geo: URI.
        Uri geoUri = Uri.parse(String.format(Locale.US,
                "geo:%.6f,%.6f?q=%.6f,%.6f(%s)",
                lat, lon, lat, lon, encodedLabel));
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, geoUri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            showToast(R.string.toast_location_opened);
            return;
        } catch (Exception ignored) {
            // No geo: handler (no map application installed), fall
            // back to a web map.
        }

        // 2. Bing Maps fallback. Use cp= for the map centre and
        //    sp=point.* for the actual pin. The previous q= form was
        //    rewritten by Bing to search?q=... and dropped the marker
        //    entirely, so the page rendered with no pin and no label.
        String bingUrl = String.format(Locale.US,
                "https://cn.bing.com/maps?cp=%.6f~%.6f&lvl=14&sp=point.%.6f_%.6f_%s",
                lat, lon, lat, lon, encodedLabel);
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(bingUrl));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            showToast(R.string.toast_location_opened);
        } catch (Exception e) {
            showToast(R.string.toast_location_open_failed);
        }
    }


    // ---------- Grid dialog ----------
    // Edit or clear the station's 8-char Maidenhead grid. The grid may
    // be empty; it can be typed manually or derived from the OS location
    // via the "Get system location" button.
    private void showGridDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 20);

        final EditText etGrid = new EditText(this);
        etGrid.setHint(R.string.dlg_enter_grid);
        etGrid.setText(grid);
        etGrid.setSingleLine(true);
        layout.addView(etGrid);

        final TextView tvHint = new TextView(this);
        tvHint.setText(R.string.dlg_grid_format_hint);
        tvHint.setTextSize(12);
        tvHint.setTextColor(ansiColor(R.color.ansi_teal));
        layout.addView(tvHint);

        final TextView tvStatus = new TextView(this);
        tvStatus.setTextSize(12);
        tvStatus.setPadding(0, 8, 0, 8);
        layout.addView(tvStatus);

        final Button btnFetch = new Button(this);
        btnFetch.setText(R.string.dlg_get_location);
        layout.addView(btnFetch);

        Runnable validate = () -> {
            String val = etGrid.getText().toString().trim().toUpperCase(Locale.US);
            if (!val.equals(etGrid.getText().toString())) {
                etGrid.setText(val);
                etGrid.setSelection(val.length());
            }
            if (val.isEmpty()) {
                tvStatus.setText(R.string.dlg_grid_empty_ok);
                tvStatus.setTextColor(ansiColor(R.color.ansi_green));
            } else if (isValidGrid8(val)) {
                tvStatus.setText(R.string.dlg_callsign_valid);
                tvStatus.setTextColor(ansiColor(R.color.ansi_green));
            } else {
                tvStatus.setText(R.string.dlg_grid_invalid_hint);
                tvStatus.setTextColor(ansiColor(R.color.ansi_red));
            }
        };
        validate.run();

        etGrid.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { validate.run(); }
        });

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.dlg_set_grid);
        builder.setView(layout);
        builder.setPositiveButton(R.string.dlg_ok, (d, w) -> {
            String val = etGrid.getText().toString().trim().toUpperCase(Locale.US);
            if (!val.isEmpty() && !isValidGrid8(val)) {
                showToast(R.string.dlg_grid_invalid_hint);
                return;
            }
            grid = val;
            saveConfig();
            btnGrid.setText(grid.isEmpty() ? getString(R.string.gui_grid) : grid);
            appendStatusMessage(getString(R.string.status_grid_updated,
                    grid.isEmpty() ? getString(R.string.dlg_grid_empty_ok) : grid));
        });
        builder.setNegativeButton(R.string.dlg_cancel, null);
        builder.create().show();

        btnFetch.setOnClickListener(v -> {
            boolean fineGranted = ContextCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            boolean coarseGranted = ContextCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            if (!fineGranted && !coarseGranted) {
                // Store the fetch action so onRequestPermissionsResult()
                // can run it after the system dialog returns.
                pendingLocationPermissionAction =
                        () -> doFetchLocationForGrid(etGrid, tvStatus, btnFetch);
                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                }, LOCATION_PERMISSION_REQ_CODE);
                return;
            }
            doFetchLocationForGrid(etGrid, tvStatus, btnFetch);
        });
    }

    private void doFetchLocationForGrid(final EditText etGrid,
                                        final TextView tvStatus,
                                        final Button btnFetch) {
        btnFetch.setEnabled(false);
        tvStatus.setText(R.string.status_location_fetching);
        tvStatus.setTextColor(ansiColor(R.color.ansi_blue));
        fetchGridFromSystemLocation(gridValue -> runOnUiThread(() -> {
            if (gridValue != null) {
                etGrid.setText(gridValue);
                etGrid.setSelection(gridValue.length());
            }
            btnFetch.setEnabled(true);
        }));
    }

    // Fetch the OS location on the main thread and convert it to an
    // 8-char Maidenhead grid. Calls back with null on failure (the
    // caller just re-enables the UI).
    private void fetchGridFromSystemLocation(final java.util.function.Consumer<String> callback) {
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            showToast(R.string.toast_location_unavailable);
            callback.accept(null);
            return;
        }
        Location best = null;
        try {
            for (String provider : lm.getProviders(true)) {
                Location loc = lm.getLastKnownLocation(provider);
                if (loc == null) continue;
                if (best == null || loc.getAccuracy() < best.getAccuracy()) {
                    best = loc;
                }
            }
        } catch (SecurityException e) {
            showToast(R.string.toast_location_permission_denied);
            callback.accept(null);
            return;
        }
        if (best != null) {
            callback.accept(latLonToGrid8(best.getLatitude(), best.getLongitude()));
            return;
        }
        String provider;
        if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            provider = LocationManager.GPS_PROVIDER;
        } else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            provider = LocationManager.NETWORK_PROVIDER;
        } else {
            showToast(R.string.toast_location_unavailable);
            callback.accept(null);
            return;
        }
        final boolean[] done = {false};
        final LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                if (done[0]) return;
                done[0] = true;
                lm.removeUpdates(this);
                callback.accept(latLonToGrid8(location.getLatitude(), location.getLongitude()));
            }
            @Override public void onProviderDisabled(@NonNull String p) {}
            @Override public void onProviderEnabled(@NonNull String p) {}
            @Override public void onStatusChanged(String p, int status, Bundle extras) {}
        };
        try {
            lm.requestSingleUpdate(provider, listener, Looper.getMainLooper());
        } catch (SecurityException e) {
            showToast(R.string.toast_location_permission_denied);
            callback.accept(null);
            return;
        }
        mainHandler.postDelayed(() -> {
            if (!done[0]) {
                lm.removeUpdates(listener);
                showToast(R.string.toast_location_timeout);
                callback.accept(null);
            }
        }, 15000);
    }

    // ---------- Callsign edit dialog ----------
    private void showCallsignDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.dlg_callsign_title);

        final EditText input = new EditText(this);
        input.setHint(R.string.dlg_callsign_hint);
        input.setText(callsign);
        input.setMaxLines(1);

        final TextView errorView = new TextView(this);
        errorView.setPadding(40, 10, 40, 10);
        errorView.setTextColor(ansiColor(R.color.ansi_red));

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(input);
        layout.addView(errorView);
        builder.setView(layout);

        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                String text = s.toString().trim();
                if (text.isEmpty()) {
                    errorView.setText("");
                    return;
                }
                String upper = text.toUpperCase(Locale.US);
                boolean valid = isValidCallsignStrict(upper);
                if (valid) {
                    errorView.setText(R.string.dlg_callsign_valid);
                    errorView.setTextColor(ansiColor(R.color.ansi_green));
                } else {
                    errorView.setText(R.string.dlg_callsign_invalid);
                    errorView.setTextColor(ansiColor(R.color.ansi_red));
                }
            }
        };
        input.addTextChangedListener(watcher);
        watcher.afterTextChanged(input.getText());

        builder.setPositiveButton(R.string.dlg_ok, (dialog, which) -> {
            String newCallsign = input.getText().toString().trim().toUpperCase(Locale.US);
            if (!isValidCallsignStrict(newCallsign)) {
                showToast(R.string.toast_invalid_callsign);
                return;
            }
            updateCallsign(newCallsign);
            appendStatusMessage(getString(R.string.status_callsign_updated, newCallsign));
        });
        builder.setNegativeButton(R.string.dlg_cancel, null);
        builder.show();
    }

    // ---------- About dialog ----------
    private void showAboutDialog() {
        String aboutText = getString(R.string.about_content);
        if (false) aboutText = "FT8 Plus for Android\n\n" +
                "版本: 1.0\n" +
                "作者: BI3BJU\n\n" +
                "功能说明:\n" +
                "• 实时解码 FT8 信号（需要录音权限）\n" +
                "• 自定义文本透明发射（最多574字节，自动分帧）\n" +
                "• 支持多帧连续传输（i3=6, f2=1）\n" +
                "• 15秒时隙自动解码\n" +
                "• 联系人管理\n" +
                "• 呼号格式：3~7位，1~2前缀+数字+0~4字母，前缀至少一字母\n" +
                "• 收到任何 RX 消息即自动保存源呼号到联系人（源需符合呼号规则）\n" +
                "• 收到自身呼号时历史区加下划线显示（文字颜色保持分类色）\n" +
                "• 联系人最多100条，备注最大32字节\n" +
                "• 前导噪声（noise）功能：每帧发射前提前1秒播放白噪声+静默（可通过复选框开关）\n" +
                "• 发射时历史区实时显示\"发送中...\"状态\n" +
                "• 信标功能：每16个时隙（240秒）自动发射“<CALL> PLUS”，接收识别为信标并显示呼号\n" +
                "• NTP时间同步：启动时自动校准内部时间（cn.pool.ntp.org / pool.ntp.org），不修改系统时间\n" +
                "• 历史日志和状态日志持久化（精简JSON + 纯文本），自动保留最新100条\n\n" +
                "注意：本应用仅用于业余无线电 FT8 模式实验。";
        new AlertDialog.Builder(this)
                .setTitle("关于")
                .setMessage(aboutText)
                .setPositiveButton("确定", null)
                .show();
    }

    // ---------- Permission callback ----------
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQ_CODE) {
            Runnable action = pendingLocationPermissionAction;
            pendingLocationPermissionAction = null;
            if (action != null) {
                boolean anyGranted = false;
                for (int r : grantResults) {
                    if (r == PackageManager.PERMISSION_GRANTED) {
                        anyGranted = true;
                        break;
                    }
                }
                if (anyGranted) {
                    action.run();
                } else {
                    showToast(R.string.toast_location_permission_denied);
                }
            }
        }
        if (requestCode == PERMISSION_REQ_CODE) {
            boolean allGranted = true;
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    String perm = permissions[i];
                    if (perm.equals(Manifest.permission.RECORD_AUDIO)) {
                        showToast(R.string.toast_record_permission_denied);
                    }
                }
            }
            if (allGranted) {
                showToast(R.string.toast_permissions_granted);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startBackgroundMemoryRx();
                syncNtp();
            }
            // Only rebuild the FileStore on Android 10 and below:
            // granting WRITE_EXTERNAL_STORAGE switches persistence
            // from the app's internal directory to the public
            // Documents folder. On Android 11+ the store is always
            // MediaStore-based, so rebuilding would discard the
            // uriCache and force every subsequent write to attempt a
            // fresh MediaStore insert that fails with a UNIQUE
            // constraint (the physical file already exists) and then
            // fall back to a MediaScanner scan.
            //
            // Do NOT reload config.txt here: the permission callback
            // may fire (twice on MIUI) after the user has already
            // changed callsign / grid through the UI; reloading
            // would overwrite those edits.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                fileStore = null;
                getFileStore();
            }
            ensurePersistenceFiles();
            // Reload configuration and contacts now that storage permission is granted
            loadConfigWithFallback();
            loadContacts();
            loadHistoryFromFile();
            loadStatusFromFile();
            
            btnCallsign.setText(callsign.isEmpty() ? getString(R.string.btn_callsign_anonymous) : callsign);
            btnGrid.setText(grid.isEmpty() ? getString(R.string.gui_grid) : grid);
            chkPreambleNoise.setChecked(preambleNoise);
            etTxFreq.setText(String.valueOf(txFreq));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopBackgroundMemoryRx();
        mainHandler.removeCallbacks(utcDashboardRunnable);
        decodeExecutor.shutdownNow();
        logWriter.shutdown();
        if (transmissionThread != null) {
            transmissionThread.interrupt();
            try {
                transmissionThread.join(1000);
            } catch (InterruptedException ignored) {}
        }
        releaseCurrentTrack();
        synchronized (transparentBuffers) {
            for (TransparentBuffer buf : transparentBuffers.values()) {
                buf.cancelTimeout();
            }
            transparentBuffers.clear();
        }
        beaconStop.set(true);
        if (beaconThread != null) {
            try { beaconThread.interrupt(); beaconThread.join(1000); } catch (InterruptedException e) {}
            beaconThread = null;
        }
    }

    private static class UtcDashboardTicker implements Runnable {
        private final WeakReference<MainActivity> activityRef;
        UtcDashboardTicker(MainActivity activity) { this.activityRef = new WeakReference<>(activity); }
        @Override
        public void run() {
            MainActivity activity = activityRef.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            if (activity.tvUtcTime != null) {
                long now = activity.getCurrentUtcTime();
                String dateTimeStr = utcFullFormat.format(new Date(now));
                activity.tvUtcTime.setText(dateTimeStr + " UTC");
            }
            // Match the Python reference: refresh the UTC clock once per second.
            activity.mainHandler.postDelayed(this, 1000);
        }
    }
}