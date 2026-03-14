package net.wigle.wigleandroid;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import net.wigle.wigleandroid.model.Network;
import net.wigle.wigleandroid.util.Logging;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FinderFragment — Aliens-style RSSI proximity finder.
 *
 * Single-device mode: launched with ARG_MAC + ARG_LABEL.
 *   → shows RSSI fuel gauge, dBm value, trend arrow, and SparklineView.
 *
 * Earbud mode: launched with no ARG_MAC.
 *   → shows all ACCESSORIES-class devices ranked by current RSSI.
 *
 * Polls ListFragment.lameStatic.networkCache every 1 second.
 */
public class FinderFragment extends Fragment {

    public static final String ARG_MAC   = "finder_mac";
    public static final String ARG_LABEL = "finder_label";

    private static final int HISTORY_SIZE = 30;
    // RSSI range: -100 dBm (empty) → -40 dBm (full); ProgressBar max = 60
    private static final int RSSI_FLOOR = -100;
    private static final int RSSI_CEIL  = -40;

    private final Handler timer   = new Handler();
    private final AtomicBoolean finishing = new AtomicBoolean(false);
    private final List<Float> history = new ArrayList<>();

    private String targetMac;
    private boolean earbudMode;

    // Single-device UI
    private ProgressBar     gauge;
    private TextView        rssiValue;
    private TextView        rangeLabel;
    private TextView        trendView;
    private SparklineView   sparkline;

    // Earbud-mode UI
    private View            earbudHeader;
    private ListView        earbudList;
    private EarbudAdapter   earbudAdapter;

    @Override
    public void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Bundle args = getArguments();
        targetMac  = args != null ? args.getString(ARG_MAC)   : null;
        earbudMode = (targetMac == null || targetMac.isEmpty());
    }

    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             final ViewGroup container,
                             final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_finder, container, false);
    }

    @Override
    public void onViewCreated(@NonNull final View view, @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        final TextView labelView = view.findViewById(R.id.finder_label);
        final TextView macView   = view.findViewById(R.id.finder_mac);
        gauge      = view.findViewById(R.id.finder_rssi_gauge);
        rssiValue  = view.findViewById(R.id.finder_rssi_value);
        rangeLabel = view.findViewById(R.id.finder_range_label);
        trendView  = view.findViewById(R.id.finder_trend);
        sparkline  = view.findViewById(R.id.finder_sparkline);
        earbudHeader = view.findViewById(R.id.finder_earbud_header);
        earbudList   = view.findViewById(R.id.finder_earbud_list);

        if (earbudMode) {
            labelView.setText(R.string.finder_earbud_title);
            macView.setText(R.string.finder_earbud_subtitle);
            earbudHeader.setVisibility(View.VISIBLE);
            earbudList.setVisibility(View.VISIBLE);
            earbudAdapter = new EarbudAdapter();
            earbudList.setAdapter(earbudAdapter);
            earbudList.setOnItemClickListener((parent, v, pos, id) -> {
                final Network net = earbudAdapter.getItem(pos);
                if (net == null) return;
                switchToSingleDevice(net);
            });
        } else {
            final Bundle args = getArguments();
            final String label = args != null ? args.getString(ARG_LABEL, targetMac) : targetMac;
            labelView.setText(label != null ? label : targetMac);
            macView.setText(targetMac);
        }
    }

    private void setupTimer() {
        timer.removeCallbacks(pollRunnable);
        timer.postDelayed(pollRunnable, 300L);
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!finishing.get()) {
                poll();
                timer.postDelayed(this, 1000L);
            }
        }
    };

    private void poll() {
        final net.wigle.wigleandroid.model.ConcurrentLinkedHashMap<String, Network> cache =
                ListFragment.lameStatic.networkCache;
        if (cache == null) return;

        if (earbudMode) {
            pollEarbudMode(cache);
        } else {
            pollSingleDevice(cache);
        }
    }

    private void pollSingleDevice(final net.wigle.wigleandroid.model.ConcurrentLinkedHashMap<String, Network> cache) {
        Network net = null;
        // Case-insensitive MAC lookup
        for (final java.util.Map.Entry<String, Network> e : cache.entrySet()) {
            if (targetMac.equalsIgnoreCase(e.getKey())) { net = e.getValue(); break; }
        }

        final float rssi = (net != null) ? net.getLevel() : Float.NaN;
        synchronized (history) {
            if (!Float.isNaN(rssi)) {
                history.add(rssi);
                if (history.size() > HISTORY_SIZE) history.remove(0);
            }
        }

        final float rssiSnap = rssi;
        final List<Float> histSnap;
        synchronized (history) { histSnap = new ArrayList<>(history); }

        final Activity activity = getActivity();
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            if (Float.isNaN(rssiSnap)) {
                rangeLabel.setText(R.string.finder_waiting);
                gauge.setProgress(0);
                rssiValue.setText("— dBm");
                trendView.setText("  ");
                return;
            }
            final int progress = Math.max(0, Math.min(60, (int)(rssiSnap - RSSI_FLOOR)));
            gauge.setProgress(progress);
            rssiValue.setText(String.format(java.util.Locale.US, "%.0f dBm", rssiSnap));
            rangeLabel.setText(rangeLabel(rssiSnap));
            trendView.setText(trend(histSnap));

            for (final Float r : histSnap) sparkline.addReading(r);
        });
    }

    private void pollEarbudMode(final net.wigle.wigleandroid.model.ConcurrentLinkedHashMap<String, Network> cache) {
        final List<Network> earbuds = new ArrayList<>();
        for (final Network net : cache.values()) {
            if (DeviceClassifier.classify(net) == DeviceClassifier.DeviceClass.ACCESSORIES) {
                earbuds.add(net);
            }
        }
        Collections.sort(earbuds, (a, b) -> Integer.compare(b.getLevel(), a.getLevel()));

        final Activity activity = getActivity();
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            if (earbudAdapter != null) earbudAdapter.setData(earbuds);
            if (rangeLabel != null)
                rangeLabel.setText(earbuds.size() + " accessory device"
                        + (earbuds.size() == 1 ? "" : "s") + " nearby");
        });
    }

    /** Switch from earbud-mode to single-device mode for the tapped device. */
    private void switchToSingleDevice(final Network net) {
        final Bundle args = new Bundle();
        args.putString(ARG_MAC,   net.getBssid());
        args.putString(ARG_LABEL, (net.getSsid() != null && !net.getSsid().isEmpty())
                ? net.getSsid() : net.getBssid());
        final FinderFragment finder = new FinderFragment();
        finder.setArguments(args);
        try {
            requireActivity().getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.tabcontent, finder, "FinderFragment")
                    .addToBackStack(null)
                    .commit();
        } catch (Exception e) {
            Logging.error("FinderFragment: switch to single device failed: ", e);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        finishing.set(false);
        setupTimer();
        final Activity a = getActivity();
        if (a != null) a.setTitle(earbudMode ? R.string.finder_earbud_title : R.string.tab_finder);
    }

    @Override
    public void onPause() {
        super.onPause();
        timer.removeCallbacks(pollRunnable);
    }

    @Override
    public void onDestroy() {
        finishing.set(true);
        super.onDestroy();
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private static String rangeLabel(final float rssi) {
        if (rssi >= -55) return "HOT  (~1-2 m)";
        if (rssi >= -65) return "WARM (~3-5 m)";
        if (rssi >= -75) return "NEAR (~5-10 m)";
        if (rssi >= -85) return "FAR  (~10-20 m)";
        return               "WEAK (>20 m)";
    }

    private static String trend(final List<Float> hist) {
        if (hist.size() < 2) return "  ";
        final float delta = hist.get(hist.size() - 1) - hist.get(hist.size() - 2);
        if (delta >  3f) return "↑↑";
        if (delta >  0f) return "↑ ";
        if (delta < -3f) return "↓↓";
        if (delta <  0f) return "↓ ";
        return "→ ";
    }

    // ---------------------------------------------------------------------------
    // Earbud-mode adapter
    // ---------------------------------------------------------------------------

    private class EarbudAdapter extends ArrayAdapter<Network> {
        private final List<Network> data = new ArrayList<>();

        EarbudAdapter() { super(requireContext(), R.layout.class_device_list_item); }

        void setData(final List<Network> newData) {
            data.clear();
            data.addAll(newData);
            notifyDataSetChanged();
        }

        @Override public int getCount() { return data.size(); }
        @Override public Network getItem(final int i) { return data.get(i); }

        @Override
        public @NonNull View getView(final int pos, @Nullable View cv,
                                     @NonNull final ViewGroup parent) {
            if (cv == null) {
                cv = LayoutInflater.from(getContext())
                        .inflate(R.layout.class_device_list_item, parent, false);
            }
            final Network net = getItem(pos);
            if (net == null) return cv;

            final int color = DeviceClassifier.colorForClass(DeviceClassifier.DeviceClass.ACCESSORIES);
            final View dot = cv.findViewById(R.id.class_color_dot);
            final GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(color);
            dot.setBackground(shape);

            final String ssid = net.getSsid();
            ((TextView) cv.findViewById(R.id.class_item_ssid))
                    .setText((ssid != null && !ssid.isEmpty()) ? ssid : "(unnamed)");
            ((TextView) cv.findViewById(R.id.class_item_mac))
                    .setText(net.getBssid() != null ? net.getBssid() : "");
            ((TextView) cv.findViewById(R.id.class_item_rssi))
                    .setText(net.getLevel() + " dBm");

            final TextView classLabel = cv.findViewById(R.id.class_item_class);
            classLabel.setText(rangeLabel(net.getLevel()));
            classLabel.setTextColor(color);

            return cv;
        }
    }
}
