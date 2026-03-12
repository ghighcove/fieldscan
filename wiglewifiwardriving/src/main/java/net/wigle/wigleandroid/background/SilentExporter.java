package net.wigle.wigleandroid.background;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Bundle;

import net.wigle.wigleandroid.db.DatabaseHelper;
import net.wigle.wigleandroid.model.Network;
import net.wigle.wigleandroid.util.FileAccess;
import net.wigle.wigleandroid.util.Logging;
import net.wigle.wigleandroid.util.PreferenceKeys;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.DuplicateHeaderMode;

import java.io.OutputStream;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.text.DecimalFormat;
import java.text.FieldPosition;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * FieldScan: writes CSV.gz export silently — no BackgroundGuiHandler, no share sheet,
 * no progress dialog. Used by AutoSyncManager's Sync to PC button.
 */
public final class SilentExporter {

    private static final String COMMA   = ",";
    private static final String NEWLINE = "\n";
    private static final String ENCODING = "UTF-8";

    private static final CSVFormat CSV_FORMAT;

    static {
        final CSVFormat.Builder builder = CSVFormat.Builder.create();
        builder.setDelimiter(',');
        builder.setQuote('"');
        builder.setRecordSeparator("\n");
        builder.setIgnoreEmptyLines(true);
        builder.setDuplicateHeaderMode(DuplicateHeaderMode.ALLOW_ALL);
        CSV_FORMAT = builder.build();
    }

    private SilentExporter() {}

    /**
     * Write a CSV.gz snapshot of the current DB to /sdcard/wiglewifi/ silently.
     * Safe to call from any background thread. No UI interaction whatsoever.
     *
     * @param context    app context
     * @param dbHelper   database
     * @param onComplete called on the same thread when writing finishes (success or error)
     */
    public static void exportAndThen(final Context context, final DatabaseHelper dbHelper,
                                     final Runnable onComplete) {
        try {
            final SharedPreferences prefs = context.getSharedPreferences(PreferenceKeys.SHARED_PREFS, 0);
            final long maxId = prefs.getLong(PreferenceKeys.PREF_DB_MARKER, 0L);

            final Bundle bundle = new Bundle();
            try (OutputStream fos = FileAccess.getOutputStream(context, bundle, new Object[2])) {
                writeRows(context, fos, dbHelper, maxId);
            }
            Logging.info("SilentExporter: export complete");
        } catch (final Exception ex) {
            Logging.error("SilentExporter error: " + ex, ex);
        } finally {
            if (onComplete != null) onComplete.run();
        }
    }

    private static void writeRows(final Context context, final OutputStream fos,
                                  final DatabaseHelper dbHelper, final long sinceId) throws Exception {
        final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

        final PackageManager pm = context.getPackageManager();
        final PackageInfo pi = pm.getPackageInfo(context.getPackageName(), 0);

        // header line
        final StringBuffer headerBuffer = new StringBuffer();
        final CSVPrinter headerPrinter = new CSVPrinter(headerBuffer, CSV_FORMAT);
        headerPrinter.printRecord(
                "WigleWifi-1.6",
                "appRelease=" + pi.versionName,
                "model=" + android.os.Build.MODEL,
                "release=" + android.os.Build.VERSION.RELEASE,
                "device=" + android.os.Build.DEVICE,
                "display=" + android.os.Build.DISPLAY,
                "board=" + android.os.Build.BOARD,
                "brand=" + android.os.Build.BRAND,
                "star=Sol",
                "body=3",
                "subBody=0"
        );
        headerBuffer.append(ObservationUploader.CSV_COLUMN_HEADERS).append(NEWLINE);
        fos.write(headerBuffer.toString().getBytes(ENCODING));

        // data rows
        final Cursor cursor = dbHelper.locationIterator(sinceId);
        if (cursor == null) return;
        try {
            final int total = cursor.getCount();
            if (total == 0) return;

            ByteBuffer byteBuffer = ByteBuffer.allocate(1024);
            CharBuffer charBuffer = CharBuffer.allocate(1024);
            final CSVPrinter printer = new CSVPrinter(charBuffer, CSV_FORMAT);
            final CharsetEncoder encoder = Charset.forName(ENCODING).newEncoder();
            encoder.onUnmappableCharacter(CodingErrorAction.REPLACE);
            final NumberFormat nf = NumberFormat.getNumberInstance(Locale.US);
            nf.setGroupingUsed(false);
            if (nf instanceof DecimalFormat) ((DecimalFormat) nf).setMaximumFractionDigits(16);
            final StringBuffer sb = new StringBuffer();
            final FieldPosition fp = new FieldPosition(NumberFormat.INTEGER_FIELD);
            final Date date = new Date();

            for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
                final String bssid = cursor.getString(1);
                final Network network = dbHelper.getNetwork(bssid);
                if (network == null) continue;

                charBuffer.clear();
                byteBuffer.clear();
                try {
                    printer.print(network.getBssid());
                    printer.print(network.getSsid());
                    printer.print(network.getCapabilities());
                    charBuffer.append(COMMA);
                    date.setTime(cursor.getLong(7));
                    FileAccess.singleCopyDateFormat(dateFormat, sb, charBuffer, fp, date);
                    charBuffer.append(COMMA);
                    final Integer channel = network.getChannel();
                    if (channel != null) FileAccess.singleCopyNumberFormat(nf, sb, charBuffer, fp, channel);
                    charBuffer.append(COMMA);
                    final int freq = network.getFrequency();
                    if (freq != 0) FileAccess.singleCopyNumberFormat(nf, sb, charBuffer, fp, freq);
                    charBuffer.append(COMMA);
                    FileAccess.singleCopyNumberFormat(nf, sb, charBuffer, fp, cursor.getInt(2));
                    charBuffer.append(COMMA);
                    FileAccess.singleCopyNumberFormat(nf, sb, charBuffer, fp, cursor.getDouble(3));
                    charBuffer.append(COMMA);
                    FileAccess.singleCopyNumberFormat(nf, sb, charBuffer, fp, cursor.getDouble(4));
                    charBuffer.append(COMMA);
                    FileAccess.singleCopyNumberFormat(nf, sb, charBuffer, fp, cursor.getDouble(5));
                    charBuffer.append(COMMA);
                    FileAccess.singleCopyNumberFormat(nf, sb, charBuffer, fp, cursor.getDouble(6));
                    printer.print(network.getRcoisOrBlank());
                    charBuffer.append(COMMA);
                    final int mfgrid = cursor.getInt(8);
                    if (mfgrid != 0) FileAccess.singleCopyNumberFormat(nf, sb, charBuffer, fp, mfgrid);
                    printer.print(network.getType().name());
                    printer.println();
                } catch (final BufferOverflowException ex) {
                    charBuffer = CharBuffer.allocate(charBuffer.capacity() * 2);
                    byteBuffer = ByteBuffer.allocate(byteBuffer.capacity() * 2);
                    cursor.moveToPrevious();
                    continue;
                }

                charBuffer.flip();
                encoder.reset();
                encoder.encode(charBuffer, byteBuffer, true);
                try { encoder.flush(byteBuffer); } catch (IllegalStateException ignored) { continue; }

                final int end = byteBuffer.position();
                fos.write(byteBuffer.array(), byteBuffer.arrayOffset(), end + byteBuffer.arrayOffset());
            }
        } finally {
            cursor.close();
        }
    }
}
