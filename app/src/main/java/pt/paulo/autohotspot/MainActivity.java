package pt.paulo.autohotspot;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int REQ_BT = 1001;
    private static final String PREFS = "auto_hotspot_prefs";
    private static final String KEY_ADDR = "device_address";
    private static final String KEY_NAME = "device_name";
    private static final String KEY_ENABLED = "automation_enabled";

    private final ArrayList<DeviceItem> devices = new ArrayList<>();
    private Spinner spinner;
    private CheckBox enabled;
    private TextView status;
    private SharedPreferences prefs;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        spinner = findViewById(R.id.deviceSpinner);
        enabled = findViewById(R.id.enabledCheck);
        status = findViewById(R.id.statusText);
        Button save = findViewById(R.id.saveButton);
        Button access = findViewById(R.id.accessibilityButton);
        Button on = findViewById(R.id.testOnButton);
        Button off = findViewById(R.id.testOffButton);
        Button settings = findViewById(R.id.appSettingsButton);

        enabled.setChecked(prefs.getBoolean(KEY_ENABLED, true));
        enabled.setOnCheckedChangeListener((v, checked) -> {
            prefs.edit().putBoolean(KEY_ENABLED, checked).apply();
            refreshStatus();
        });
        save.setOnClickListener(v -> saveCar());
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        on.setOnClickListener(v -> test(true));
        off.setOnClickListener(v -> test(false));
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));
        requestBtAndLoad();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void requestBtAndLoad() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
        } else loadDevices();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BT && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadDevices();
        } else if (requestCode == REQ_BT) {
            Toast.makeText(this, "É necessária autorização Bluetooth.", Toast.LENGTH_LONG).show();
        }
    }

    private void loadDevices() {
        devices.clear();
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) return;
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) for (BluetoothDevice d : bonded) {
                String name = d.getName();
                if (TextUtils.isEmpty(name)) name = "Dispositivo Bluetooth";
                devices.add(new DeviceItem(name, d.getAddress()));
            }
        } catch (SecurityException ignored) { }
        devices.sort(Comparator.comparing(a -> a.name.toLowerCase()));
        ArrayAdapter<DeviceItem> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, devices);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(a);
        String saved = prefs.getString(KEY_ADDR, null);
        if (saved != null) for (int i = 0; i < devices.size(); i++)
            if (saved.equalsIgnoreCase(devices.get(i).address)) spinner.setSelection(i);
        refreshStatus();
    }

    private void saveCar() {
        if (spinner.getSelectedItem() == null) {
            Toast.makeText(this, "Não encontrei dispositivos emparelhados.", Toast.LENGTH_LONG).show();
            return;
        }
        DeviceItem d = (DeviceItem) spinner.getSelectedItem();
        prefs.edit().putString(KEY_ADDR, d.address).putString(KEY_NAME, d.name).apply();
        Toast.makeText(this, "Carro guardado: " + d.name, Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    private void test(boolean on) {
        CarHotspotAccessibilityService s = CarHotspotAccessibilityService.getRunningInstance();
        if (s == null) {
            Toast.makeText(this, "Ativa primeiro o serviço em Acessibilidade.", Toast.LENGTH_LONG).show();
            return;
        }
        s.requestHotspot(on);
    }

    private void refreshStatus() {
        if (status == null) return;
        String car = prefs.getString(KEY_NAME, "não escolhido");
        status.setText("Estado: carro = " + car + "\nAutomação: " +
                (prefs.getBoolean(KEY_ENABLED, true) ? "ativa" : "desativada") +
                "\nAcessibilidade: " + (isAccessibilityEnabled() ? "ativa" : "não ativada"));
    }

    private boolean isAccessibilityEnabled() {
        ComponentName target = new ComponentName(this, CarHotspotAccessibilityService.class);
        String list = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list == null) return false;
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(list);
        while (splitter.hasNext()) if (target.equals(ComponentName.unflattenFromString(splitter.next()))) return true;
        return false;
    }

    private static class DeviceItem {
        final String name, address;
        DeviceItem(String n, String a) { name = n; address = a; }
        @Override public String toString() { return name + "  (" + address + ")"; }
    }
}
