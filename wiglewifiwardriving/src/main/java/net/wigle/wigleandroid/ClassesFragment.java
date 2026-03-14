package net.wigle.wigleandroid;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import net.wigle.wigleandroid.model.Network;
import net.wigle.wigleandroid.util.Logging;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ClassesFragment — filtered device list by DeviceClassifier class.
 *
 * Tab strip: All · Medical · Car · Accessories · Family · Open WiFi · Phone
 * Polls ListFragment.lameStatic.networkCache every 2 seconds.
 * Tap a device → launches FinderFragment for that device.
 */
public class ClassesFragment extends Fragment {

    private final Handler timer = new Handler();
    private final AtomicBoolean finishing = new AtomicBoolean(false);

    private DeviceClassifier.DeviceClass currentFilter = null; // null = All
    private ClassesListAdapter adapter;
    private TextView countView;

    @Override
    public void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Load synced config + kick off DB refresh on background thread
        final Context ctx = getContext();
        if (ctx != null) {
            DeviceClassifier.loadSyncedConfig(ctx);
            new Thread(() -> {
                final MainActivity main = MainActivity.getMainActivity();
                if (main != null) {
                    DeviceClassifier.refreshFromDB(ListFragment.lameStatic.dbHelper);
                }
            }, "classifier-db-refresh").start();
        }
    }

    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             final ViewGroup container,
                             final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_classes, container, false);
    }

    @Override
    public void onViewCreated(@NonNull final View view, @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        countView = view.findViewById(R.id.classes_count);

        // Wire tab strip
        final RadioGroup tabGroup = view.findViewById(R.id.class_tab_group);
        tabGroup.setOnCheckedChangeListener((group, checkedId) -> {
            currentFilter = classForTabId(checkedId);
            refreshList();
        });

        // List + adapter
        adapter = new ClassesListAdapter();
        final ListView listView = view.findViewById(R.id.classes_list);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, v, position, id) -> {
            final Network net = adapter.getItem(position);
            if (net == null) return;
            launchFinder(net);
        });

        setupTimer();
    }

    private void setupTimer() {
        timer.removeCallbacks(refreshRunnable);
        timer.postDelayed(refreshRunnable, 500);
    }

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!finishing.get()) {
                refreshList();
                timer.postDelayed(this, 2000L);
            }
        }
    };

    private void refreshList() {
        if (adapter == null || isDetached()) return;
        final net.wigle.wigleandroid.model.ConcurrentLinkedHashMap<String, Network> cache =
                ListFragment.lameStatic.networkCache;
        if (cache == null) return;

        final List<Network> filtered = new ArrayList<>();
        for (final Network net : cache.values()) {
            final DeviceClassifier.DeviceClass cls = DeviceClassifier.classify(net);
            if (currentFilter == null || cls == currentFilter) {
                filtered.add(net);
            }
        }

        // Sort: strongest RSSI first
        Collections.sort(filtered, (a, b) -> Integer.compare(b.getLevel(), a.getLevel()));

        final Activity activity = getActivity();
        if (activity != null) {
            activity.runOnUiThread(() -> {
                adapter.setData(filtered);
                if (countView != null) {
                    final String label = currentFilter == null ? "All"
                            : DeviceClassifier.label(currentFilter);
                    countView.setText(filtered.size() + " " + label + " device"
                            + (filtered.size() == 1 ? "" : "s"));
                }
            });
        }
    }

    private void launchFinder(final Network net) {
        final Bundle args = new Bundle();
        args.putString(FinderFragment.ARG_MAC,   net.getBssid());
        args.putString(FinderFragment.ARG_LABEL,
                (net.getSsid() != null && !net.getSsid().isEmpty()) ? net.getSsid() : net.getBssid());
        final FinderFragment finder = new FinderFragment();
        finder.setArguments(args);
        try {
            requireActivity().getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.tabcontent, finder, "FinderFragment")
                    .addToBackStack(null)
                    .commit();
        } catch (Exception e) {
            Logging.error("ClassesFragment: failed to launch finder: ", e);
        }
    }

    private static DeviceClassifier.DeviceClass classForTabId(final int id) {
        if (id == R.id.tab_medical)     return DeviceClassifier.DeviceClass.MEDICAL;
        if (id == R.id.tab_car)         return DeviceClassifier.DeviceClass.CAR;
        if (id == R.id.tab_accessories) return DeviceClassifier.DeviceClass.ACCESSORIES;
        if (id == R.id.tab_family)      return DeviceClassifier.DeviceClass.FAMILY;
        if (id == R.id.tab_open)        return DeviceClassifier.DeviceClass.OPEN_WIFI;
        if (id == R.id.tab_phone)       return DeviceClassifier.DeviceClass.PHONE;
        return null; // All
    }

    @Override
    public void onResume() {
        super.onResume();
        finishing.set(false);
        setupTimer();
        final Activity a = getActivity();
        if (a != null) a.setTitle(R.string.tab_classes);
    }

    @Override
    public void onPause() {
        super.onPause();
        timer.removeCallbacks(refreshRunnable);
    }

    @Override
    public void onDestroy() {
        finishing.set(true);
        super.onDestroy();
    }

    // ---------------------------------------------------------------------------
    // List Adapter
    // ---------------------------------------------------------------------------

    private class ClassesListAdapter extends ArrayAdapter<Network> {
        private final List<Network> data = new ArrayList<>();

        ClassesListAdapter() {
            super(requireContext(), R.layout.class_device_list_item);
        }

        void setData(final List<Network> newData) {
            data.clear();
            data.addAll(newData);
            notifyDataSetChanged();
        }

        @Override public int getCount() { return data.size(); }
        @Override public Network getItem(final int i) { return data.get(i); }

        @Override
        public @NonNull View getView(final int position, @Nullable View convertView,
                                     @NonNull final ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext())
                        .inflate(R.layout.class_device_list_item, parent, false);
            }
            final Network net = getItem(position);
            if (net == null) return convertView;

            final DeviceClassifier.DeviceClass cls = DeviceClassifier.classify(net);
            final int color = DeviceClassifier.colorForClass(cls);

            // Color dot
            final View dot = convertView.findViewById(R.id.class_color_dot);
            final GradientDrawable dotShape = new GradientDrawable();
            dotShape.setShape(GradientDrawable.OVAL);
            dotShape.setColor(color);
            dot.setBackground(dotShape);

            final TextView ssidView = convertView.findViewById(R.id.class_item_ssid);
            final String ssid = net.getSsid();
            ssidView.setText((ssid != null && !ssid.isEmpty()) ? ssid : "(no name)");

            final TextView macView = convertView.findViewById(R.id.class_item_mac);
            macView.setText(net.getBssid() != null ? net.getBssid() : "");

            final TextView rssiView = convertView.findViewById(R.id.class_item_rssi);
            rssiView.setText(net.getLevel() + " dBm");

            final TextView classView = convertView.findViewById(R.id.class_item_class);
            classView.setText(DeviceClassifier.label(cls));
            classView.setTextColor(color);

            return convertView;
        }
    }
}
