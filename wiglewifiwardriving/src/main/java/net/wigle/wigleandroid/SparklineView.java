package net.wigle.wigleandroid;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Simple sparkline view — draws RSSI history as a line chart.
 * Push readings via addReading(float rssi); clears via clear().
 */
public class SparklineView extends View {

    private static final int MAX_READINGS = 30;
    private static final float RSSI_MIN = -100f;
    private static final float RSSI_MAX = -40f;

    private final List<Float> readings = new ArrayList<>();
    private final Paint linePaint;
    private final Paint dotPaint;
    private final Paint gridPaint;
    private final Paint labelPaint;

    public SparklineView(final Context ctx) {
        this(ctx, null);
    }

    public SparklineView(final Context ctx, final AttributeSet attrs) {
        super(ctx, attrs);

        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(0xFF00E676);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2.5f);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setColor(0xFF00E676);
        dotPaint.setStyle(Paint.Style.FILL);

        gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setColor(0xFF333333);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f);

        labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setColor(0xFF555555);
        labelPaint.setTextSize(22f);
    }

    public synchronized void addReading(final float rssi) {
        readings.add(rssi);
        if (readings.size() > MAX_READINGS) readings.remove(0);
        postInvalidate();
    }

    public synchronized void clear() {
        readings.clear();
        postInvalidate();
    }

    @Override
    protected synchronized void onDraw(final Canvas canvas) {
        super.onDraw(canvas);
        final int w = getWidth();
        final int h = getHeight();
        final int pad = 12;

        // Grid lines at -40, -60, -80, -100 dBm
        for (int rssi : new int[]{-40, -60, -80, -100}) {
            final float y = rssiToY(rssi, h, pad);
            canvas.drawLine(pad, y, w - pad, y, gridPaint);
            canvas.drawText(rssi + "", pad + 2, y - 2, labelPaint);
        }

        if (readings.size() < 2) return;

        final float xStep = (float)(w - 2 * pad) / (MAX_READINGS - 1);
        final int startIdx = MAX_READINGS - readings.size();

        final Path path = new Path();
        boolean first = true;

        for (int i = 0; i < readings.size(); i++) {
            final float x = pad + (startIdx + i) * xStep;
            final float y = rssiToY(readings.get(i), h, pad);
            if (first) { path.moveTo(x, y); first = false; }
            else       { path.lineTo(x, y); }
        }
        canvas.drawPath(path, linePaint);

        // Dot at most recent reading
        final float lastX = pad + (startIdx + readings.size() - 1) * xStep;
        final float lastY = rssiToY(readings.get(readings.size() - 1), h, pad);
        canvas.drawCircle(lastX, lastY, 5f, dotPaint);
    }

    private float rssiToY(final float rssi, final int h, final int pad) {
        final float norm = (rssi - RSSI_MIN) / (RSSI_MAX - RSSI_MIN); // 0=weak, 1=strong
        return h - pad - norm * (h - 2f * pad);
    }
}
