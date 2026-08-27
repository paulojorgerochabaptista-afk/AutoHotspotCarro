package pt.paulo.autohotspot;

import android.accessibilityservice.AccessibilityService;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.text.Normalizer;
import java.util.Locale;

public class CarHotspotAccessibilityService extends AccessibilityService {
    private static final String TAG = "AutoHotspotCarro";
    private static final String PREFS = "auto_hotspot_prefs";
    private static final String KEY_ADDR = "device_address";
    private static final String KEY_ENABLED = "automation_enabled";
    private static volatile CarHotspotAccessibilityService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Boolean desiredState;
    private boolean receiverRegistered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (!BluetoothDevice.ACTION_ACL_CONNECTED.equals(action) &&
                    !BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) return;

            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (!p.getBoolean(KEY_ENABLED, true)) return;
            String target = p.getString(KEY_ADDR, null);
            if (TextUtils.isEmpty(target)) return;

            BluetoothDevice d;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            else d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (d == null) return;

            try {
                if (!target.equalsIgnoreCase(d.getAddress())) return;
            } catch (SecurityException e) {
                Log.e(TAG, "Sem permissão Bluetooth", e);
                return;
            }
            requestHotspot(BluetoothDevice.ACTION_ACL_CONNECTED.equals(action));
        }
    };

    public static CarHotspotAccessibilityService getRunningInstance() { return instance; }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(receiver, f);
        receiverRegistered = true;
    }

    public void requestHotspot(boolean on) {
        desiredState = on;
        boolean opened = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
        if (!opened) {
            Log.e(TAG, "Não foi possível abrir os atalhos rápidos");
            return;
        }
        handler.postDelayed(() -> findAndToggle(0), 650L);
    }

    private void findAndToggle(int attempt) {
        if (desiredState == null) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) { retry(attempt); return; }
        AccessibilityNodeInfo hotspot = findHotspot(root);
        if (hotspot == null) { retry(attempt); return; }

        AccessibilityNodeInfo clickable = hotspot;
        while (clickable != null && !clickable.isClickable()) clickable = clickable.getParent();
        if (clickable == null) { retry(attempt); return; }

        Boolean current = inferState(hotspot, clickable);
        if (current != null && current.equals(desiredState)) {
            finish();
            return;
        }
        if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            handler.postDelayed(this::finish, 900L);
        else retry(attempt);
    }

    private void retry(int attempt) {
        if (attempt >= 8) {
            Log.e(TAG, "Botão Hotspot não encontrado. Coloca-o na primeira página dos atalhos rápidos.");
            finish();
            return;
        }
        handler.postDelayed(() -> findAndToggle(attempt + 1), 350L);
    }

    private AccessibilityNodeInfo findHotspot(AccessibilityNodeInfo n) {
        if (n == null) return null;
        String all = normalize(n.getText()) + " " + normalize(n.getContentDescription());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) all += " " + normalize(n.getStateDescription());
        if (all.contains("hotspot") || all.contains("ponto de acesso") || all.contains("ponto acesso") ||
                all.contains("zona wi-fi") || all.contains("zona wifi") || all.contains("personal hotspot") ||
                all.contains("mobile hotspot")) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo found = findHotspot(n.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private Boolean inferState(AccessibilityNodeInfo a, AccessibilityNodeInfo b) {
        if (a.isCheckable()) return a.isChecked();
        if (b.isCheckable()) return b.isChecked();
        String t = normalize(a.getText()) + " " + normalize(a.getContentDescription()) +
                " " + normalize(b.getText()) + " " + normalize(b.getContentDescription());
        if (t.contains("desligado") || t.contains("desativado") || t.contains("disabled") || t.contains(" off ")) return false;
        if (t.contains("ligado") || t.contains("ativado") || t.contains("enabled") || t.contains(" on ")) return true;
        return null;
    }

    private String normalize(CharSequence s) {
        if (s == null) return "";
        return " " + Normalizer.normalize(s.toString(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).trim() + " ";
    }

    private void finish() {
        desiredState = null;
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }

    @Override public void onDestroy() {
        instance = null;
        if (receiverRegistered) {
            try { unregisterReceiver(receiver); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }
}
