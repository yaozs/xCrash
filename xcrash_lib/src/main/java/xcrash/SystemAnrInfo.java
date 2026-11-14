// Copyright (c) 2019-present, iQIYI, Inc. All rights reserved.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.
//

// Created by xCrash maintainers.

package xcrash;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 通过系统 {@link ApplicationExitInfo}（Android 11 / API 30 起）读取系统侧记录的 ANR 信息。
 *
 * <p>它解决的正是 xCrash 自身 SIGQUIT 方案的三个短板：
 * <ul>
 *   <li>没有 ANR 分类（输入超时 / 广播超时 / 服务超时 / ContentProvider 超时）；</li>
 *   <li>没有 CPU 负载与各进程 CPU 占用，无法区分"CPU 被抢"和"真死锁"；</li>
 *   <li>看不到系统侧的其它进程采样。</li>
 * </ul>
 *
 * <p>注意：这份记录由系统在<strong>进程死亡</strong>时落盘，因此
 * <ul>
 *   <li>ANR 后进程被系统或用户杀掉，下次启动即可读到；</li>
 *   <li>如果 ANR 之后进程活了下来（用户点了"等待"），本条记录要等进程真正退出后才出现；</li>
 *   <li>API &lt; 30 没有该能力，只能沿用 xCrash 自身的 SIGQUIT 方案。</li>
 * </ul>
 *
 * <p>{@link #getTraceInputStream()} 返回的数据由系统以 gzip 存储，这里已经解压。
 * 无需任何权限即可读取本应用自己的记录。
 *
 * <p>一次 ANR 会产生两份文件，靠后缀区分：
 * <ul>
 *   <li>{@code tombstone_<时间>_<版本>__<进程>.anr.xcrash} ——
 *       xCrash 收到 SIGQUIT 当场抓的，含完整 Java 线程栈；</li>
 *   <li>{@code tombstone_<时间>_<版本>__<进程>.anr.system.xcrash} ——
 *       本类读出的系统侧记录，含系统给出的 ANR 原因描述和系统 trace。</li>
 * </ul>
 */
@SuppressWarnings("unused")
public final class SystemAnrInfo {

    private static final int MAX_TRACE_BYTES = 4 * 1024 * 1024;
    /** 记录上次处理到的系统记录时间戳，避免重复落盘 */
    private static final String MARKER_FILE_NAME = ".system_anr_last_ts";

    private SystemAnrInfo() {
    }

    /**
     * 一条系统记录的应用退出/ANR 信息。
     */
    public static final class Record {
        /** 进程死亡时间（毫秒）。 */
        public final long timestamp;
        /** 系统给出的原因描述，ANR 时形如 "Input dispatching timed out (...)"，可能为 null。 */
        public final String description;
        /** 系统抓取的 trace 全文（gzip 已解压），不可用时为 null。 */
        public final String trace;
        /** {@link ApplicationExitInfo} 的 reason，ANR 时为 REASON_ANR(6)。 */
        public final int reason;
        /** 进程当时的 importance，0 表示前台。 */
        public final int importance;
        /** 进程名。 */
        public final String processName;

        Record(long timestamp, String description, String trace, int reason, int importance, String processName) {
            this.timestamp = timestamp;
            this.description = description;
            this.trace = trace;
            this.reason = reason;
            this.importance = importance;
            this.processName = processName;
        }

        public boolean isAnr() {
            return reason == ApplicationExitInfo.REASON_ANR;
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "Record{ts=%d, reason=%d, importance=%d, process=%s, desc=%s, traceLen=%d}",
                timestamp, reason, importance, processName, description, trace == null ? 0 : trace.length());
        }
    }

    /**
     * 当前系统版本是否支持通过 ApplicationExitInfo 读取系统 ANR 记录。
     */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= 30;
    }

    /**
     * 读取最近的系统记录，按时间从新到旧排序。
     *
     * @param ctx    Context
     * @param maxNum 最多返回多少条，0 或负数表示不限制（系统侧本身有环形缓冲上限）
     * @return 读取失败或无数据时返回空列表，不返回 null
     */
    public static List<Record> getRecentRecords(Context ctx, int maxNum) {
        List<Record> records = new ArrayList<Record>();
        if (!isSupported() || ctx == null) {
            return records;
        }

        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return records;
            }
            // packageName 传自己的包名，无需任何权限；传别人包名才需要 DUMP 权限
            List<ApplicationExitInfo> infos = am.getHistoricalProcessExitReasons(
                ctx.getPackageName(), 0, (maxNum > 0 ? maxNum : 0));
            if (infos == null || infos.isEmpty()) {
                return records;
            }

            for (ApplicationExitInfo info : infos) {
                records.add(convert(info));
            }
        } catch (Throwable e) {
            XCrash.getLogger().w(Util.TAG, "SystemAnrInfo getRecentRecords failed", e);
        }
        return records;
    }

    /**
     * 只读取 ANR 类型的记录。
     */
    public static List<Record> getRecentAnrRecords(Context ctx, int maxNum) {
        List<Record> all = getRecentRecords(ctx, maxNum);
        List<Record> anr = new ArrayList<Record>();
        for (Record r : all) {
            if (r.isAnr()) {
                anr.add(r);
            }
        }
        return anr;
    }

    /**
     * 读取时间戳晚于 {@code sinceTimestampMs} 的 ANR 记录，用于"上次处理到哪一条"的增量拉取。
     */
    public static List<Record> getAnrRecordsSince(Context ctx, long sinceTimestampMs) {
        List<Record> anr = getRecentAnrRecords(ctx, 0);
        if (sinceTimestampMs <= 0) {
            return anr;
        }
        List<Record> result = new ArrayList<Record>();
        for (Record r : anr) {
            if (r.timestamp > sinceTimestampMs) {
                result.add(r);
            }
        }
        return result;
    }

    /**
     * 把一条记录写成与 xCrash tombstone 同格式的文本，便于复用现有的解析与上报链路。
     *
     * @param record 记录
     * @param dir    输出目录，通常用 {@link XCrash#getLogDir()}
     * @return 写入的文件；失败返回 null
     */
    public static File writeToFile(Record record, String dir) {
        if (record == null || dir == null) {
            return null;
        }

        File file = null;
        try {
            File d = new File(dir);
            if (!d.exists() && !d.mkdirs()) {
                return null;
            }
            String name = String.format(Locale.US, "%s_%020d_%s__%s%s",
                Util.logPrefix, record.timestamp * 1000,
                XCrash.getAppVersion(), record.processName == null ? "unknown" : record.processName,
                Util.anrSystemLogSuffix);
            file = new File(d, name);

            OutputStream os = new FileOutputStream(file);
            try {
                StringBuilder sb = new StringBuilder(4096);
                sb.append(Util.sepHead).append('\n');
                sb.append("Tombstone maker: '").append(Version.fullVersion).append("'\n");
                sb.append("Crash type: '").append(Util.anrCrashType).append("'\n");
                sb.append("Start time: '").append(formatTime(record.timestamp)).append("'\n");
                sb.append("Crash time: '").append(formatTime(record.timestamp)).append("'\n");
                sb.append("App ID: '").append(XCrash.getAppId()).append("'\n");
                sb.append("App version: '").append(XCrash.getAppVersion()).append("'\n");
                sb.append("API level: '").append(Build.VERSION.SDK_INT).append("'\n");
                sb.append("OS version: '").append(Build.VERSION.RELEASE).append("'\n");
                sb.append("ABI list: '").append(Util.getAbiList()).append("'\n");
                sb.append("Manufacturer: '").append(Build.MANUFACTURER).append("'\n");
                sb.append("Brand: '").append(Build.BRAND).append("'\n");
                sb.append("Model: '").append(Util.getMobileModel()).append("'\n");
                sb.append("Build fingerprint: '").append(Build.FINGERPRINT).append("'\n");
                sb.append(">>> ").append(record.processName).append(" <<<\n");
                sb.append('\n');
                sb.append("--- --- --- --- --- --- --- --- --- --- --- --- --- --- --- ---\n");
                sb.append("Mode: System ApplicationExitInfo\n");
                sb.append("Reason code: '").append(record.reason).append("'\n");
                sb.append("Importance: '").append(record.importance).append("'\n");
                sb.append("ANR reason: '").append(record.description == null ? "unknown" : record.description).append("'\n");
                sb.append("\n");
                if (record.trace != null) {
                    sb.append(record.trace);
                    sb.append('\n');
                } else {
                    sb.append("(system trace not available)\n");
                }
                os.write(sb.toString().getBytes("UTF-8"));
            } finally {
                try {
                    os.close();
                } catch (Exception ignored) {
                }
            }
        } catch (Throwable e) {
            XCrash.getLogger().e(Util.TAG, "SystemAnrInfo writeToFile failed", e);
            return null;
        }

        return file;
    }

    private static String formatTime(long timestampMs) {
        return new java.text.SimpleDateFormat(Util.timeFormatterStr, Locale.US)
            .format(new java.util.Date(timestampMs));
    }

    /**
     * 增量收集系统侧的 ANR 记录并落盘，供 {@link XCrash#init} 在后台调用。
     *
     * <p>落盘文件名形如
     * {@code tombstone_<时间>_<版本>__<进程名>.anr.system.xcrash}，
     * 与 xCrash 自己通过 SIGQUIT 抓到的 {@code .anr.xcrash} 明确区分。
     *
     * <p>用 {@code .system_anr_last_ts} 记录上次处理到的时间戳，避免每次启动重复落盘。
     *
     * @param ctx        Context
     * @param logDir     日志目录
     * @param maxRecords 单次最多读取多少条系统记录，0 表示不限制
     * @return 本次新写出的文件列表
     */
    static List<File> collectAndSave(Context ctx, String logDir, int maxRecords) {
        List<File> saved = new ArrayList<File>();
        if (!isSupported() || ctx == null || logDir == null) {
            return saved;
        }

        try {
            long lastTs = readLastTimestamp(logDir);
            List<Record> records = getRecentAnrRecords(ctx, maxRecords);
            long newest = lastTs;

            for (Record r : records) {
                if (r.timestamp <= lastTs) {
                    continue;
                }
                // trace 读不到的记录（例如 ANR 后进程因其他原因死亡）不落盘，避免产生空壳文件
                if (r.trace == null) {
                    continue;
                }
                File f = writeToFile(r, logDir);
                if (f != null) {
                    saved.add(f);
                    if (r.timestamp > newest) {
                        newest = r.timestamp;
                    }
                }
            }

            if (!saved.isEmpty()) {
                writeLastTimestamp(logDir, newest);
            }
        } catch (Throwable e) {
            XCrash.getLogger().w(Util.TAG, "SystemAnrInfo collectAndSave failed", e);
        }

        return saved;
    }

    private static File markerFile(String logDir) {
        return new File(logDir, MARKER_FILE_NAME);
    }

    private static long readLastTimestamp(String logDir) {
        BufferedReader br = null;
        try {
            File f = markerFile(logDir);
            if (!f.exists()) {
                return 0;
            }
            br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line = br.readLine();
            if (line != null) {
                return Long.parseLong(line.trim());
            }
        } catch (Throwable ignored) {
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Exception ignored) {
                }
            }
        }
        return 0;
    }

    private static void writeLastTimestamp(String logDir, long timestamp) {
        OutputStream os = null;
        try {
            os = new FileOutputStream(markerFile(logDir));
            os.write(Long.toString(timestamp).getBytes("UTF-8"));
        } catch (Throwable ignored) {
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static Record convert(ApplicationExitInfo info) {
        long timestamp = info.getTimestamp();
        String description = null;
        int reason = -1;
        int importance = -1;
        String processName = null;
        String trace = null;

        try {
            description = info.getDescription();
        } catch (Throwable ignored) {
        }
        try {
            reason = info.getReason();
        } catch (Throwable ignored) {
        }
        try {
            importance = info.getImportance();
        } catch (Throwable ignored) {
        }
        try {
            processName = info.getProcessName();
        } catch (Throwable ignored) {
        }

        // 只有 ANR 才值得去读 trace，trace 可能比较大
        if (reason == ApplicationExitInfo.REASON_ANR) {
            trace = readTrace(info);
        }

        return new Record(timestamp, description, trace, reason, importance, processName);
    }

    private static String readTrace(ApplicationExitInfo info) {
        InputStream is = null;
        try {
            // 返回的是系统侧抓取的那份 trace，内部已解 gzip
            is = info.getTraceInputStream();
            if (is == null) {
                return null;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = is.read(buf)) > 0) {
                total += n;
                if (total > MAX_TRACE_BYTES) {
                    bos.write(buf, 0, n - (total - MAX_TRACE_BYTES));
                    bos.write("\n... (trace truncated)\n".getBytes("UTF-8"));
                    break;
                }
                bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8");
        } catch (Throwable e) {
            XCrash.getLogger().w(Util.TAG, "SystemAnrInfo readTrace failed", e);
            return null;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
