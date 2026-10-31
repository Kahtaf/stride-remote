// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Kahtaf

package dev.kahtaf.walkpad;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.Map;

@SuppressLint({"MissingPermission", "SetTextI18n"})
public final class MainActivity extends Activity {
  private final Handler handler = new Handler(Looper.getMainLooper());
  private PadService service;
  private boolean bound, scanning, resumed;
  private Runnable permissionAction;
  private BluetoothLeScanner scanner;
  private ScanCallback scanCallback;
  private final Map<String, ScanResult> results = new LinkedHashMap<>();
  private String page = "", localMessage;
  private LinearLayout list, devices;
  private TextView scanStatus;
  private RemotePanel panel;
  private final Runnable refresh = this::render;
  private final ServiceConnection connection =
      new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
          service = ((PadService.LocalBinder) binder).service();
          service.observe(refresh);
          render();
        }

        public void onServiceDisconnected(ComponentName name) {
          service = null;
          page = "";
          render();
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().getDecorView().getWindowInsetsController().hide(WindowInsets.Type.systemBars());
    bound = bindService(new Intent(this, PadService.class), connection, Context.BIND_AUTO_CREATE);
    render();
  }

  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    render();
  }

  @Override
  protected void onPause() {
    resumed = false;
    stopScan();
    if (panel != null) panel.cancelConfirmation();
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    stopScan();
    handler.removeCallbacksAndMessages(null);
    if (service != null) service.unobserve(refresh);
    if (bound) unbindService(connection);
    super.onDestroy();
  }

  private SharedPreferences preferences() {
    return getSharedPreferences("pad", MODE_PRIVATE);
  }

  private void render() {
    if (!resumed && !page.isEmpty()) return;
    if (service != null && service.controller() != null) {
      if (page.equals("details") && service.controller().idle()) return;
      if (!page.equals("remote")) {
        page = "remote";
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(RemotePanel.BG);
        panel = new RemotePanel(this, this::details);
        FrameLayout.LayoutParams params =
            new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        root.addView(panel, params);
        setContentView(root);
      }
      panel.render(service.controller());
      return;
    }
    if (page.equals("details") || page.equals("scan")) return;
    if (service != null && service.connecting()) {
      if (!page.equals("connecting")) {
        column("connecting");
        title("Connecting…");
        action("Cancel", () -> service.disconnect());
      }
      return;
    }
    column("home");
    list.setGravity(Gravity.CENTER);
    title("Stride Remote");
    String message = localMessage != null ? localMessage : service == null ? "" : service.message();
    if (service != null && service.beltMayMove()) message = "Disconnected · check belt";
    if (!message.isBlank()) body(message);
    String saved = preferences().getString("address", null);
    if (saved != null)
      action(
          "Connect",
          () ->
              ensurePermissions(
                  () -> connect(saved, preferences().getString("name", "WalkingPad"))));
    Button scan =
        action(saved == null ? "Find pad" : "Change pad", () -> ensurePermissions(this::scan));
    scan.setId(R.id.scan);
  }

  private void ensurePermissions(Runnable next) {
    String[] permissions =
        new String[] {Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
    for (String permission : permissions)
      if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
        permissionAction = next;
        requestPermissions(permissions, 1);
        return;
      }
    BluetoothManager manager = getSystemService(BluetoothManager.class);
    BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
    if (adapter == null) {
      localMessage = "Bluetooth unavailable";
      page = "";
      render();
      return;
    }
    if (!adapter.isEnabled()) {
      startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
      return;
    }
    next.run();
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] permissions, int[] granted) {
    super.onRequestPermissionsResult(code, permissions, granted);
    if (code == 1) {
      boolean allowed = granted.length > 0;
      for (int result : granted) allowed &= result == PackageManager.PERMISSION_GRANTED;
      Runnable next = permissionAction;
      permissionAction = null;
      if (allowed && next != null) ensurePermissions(next);
      else if (!allowed) {
        localMessage = "Allow Nearby devices in Settings";
        page = "";
        render();
      }
    }
  }

  private void connect(String address, String name) {
    if (service == null) {
      localMessage = "Loading…";
      page = "";
      render();
      return;
    }
    stopScan();
    page = "";
    localMessage = null;
    if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
        != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 2);
    }
    startForegroundService(
        new Intent(this, PadService.class)
            .setAction(PadService.CONNECT)
            .putExtra("address", address)
            .putExtra("name", name));
  }

  private void scan() {
    stopScan();
    results.clear();
    column("scan");
    title("Devices");
    scanStatus = body("Scanning…");
    devices = new LinearLayout(this);
    devices.setOrientation(LinearLayout.VERTICAL);
    list.addView(devices);
    action("Scan again", () -> ensurePermissions(this::scan));
    BluetoothManager manager = getSystemService(BluetoothManager.class);
    scanner = manager.getAdapter().getBluetoothLeScanner();
    if (scanner == null) {
      scanStatus.setText("Enable Bluetooth");
      return;
    }
    scanning = true;
    scanCallback =
        new ScanCallback() {
          @Override
          public void onScanResult(int callbackType, ScanResult result) {
            if (!scanning || !page.equals("scan") || result.getScanRecord() == null) return;
            String name = result.getScanRecord().getDeviceName();
            if (name == null || name.isBlank()) name = result.getDevice().getName();
            if (name == null || name.isBlank()) {
              if (result.getScanRecord().getServiceUuids() == null
                  || !result
                      .getScanRecord()
                      .getServiceUuids()
                      .contains(new android.os.ParcelUuid(ClassicProtocol.SERVICE))) return;
              name = "WalkingPad";
            }
            String address = result.getDevice().getAddress();
            if (results.putIfAbsent(address, result) != null) return;
            String label = name;
            Button item =
                RemotePanel.button(
                    MainActivity.this,
                    label + "\n" + address.substring(12),
                    0xff1d2722,
                    RemotePanel.WHITE);
            item.setTextSize(12);
            item.setContentDescription(
                "Connect to " + label + ", device ending " + address.substring(12));
            item.setOnClickListener(v -> connect(address, label));
            LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
            params.topMargin = dp(6);
            devices.addView(item, params);
            scanStatus.setText("Scanning…");
          }

          @Override
          public void onScanFailed(int error) {
            stopScan();
            if (scanStatus != null) scanStatus.setText("Scan failed (" + error + ")");
          }
        };
    try {
      scanner.startScan(
          null,
          new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
          scanCallback);
    } catch (SecurityException | IllegalStateException e) {
      stopScan();
      scanStatus.setText("Check Bluetooth and Nearby devices permission");
    }
    ScanCallback active = scanCallback;
    handler.postDelayed(
        () -> {
          if (scanCallback != active) return;
          stopScan();
          scanStatus.setText(results.isEmpty() ? "No devices found" : "Select a device");
        },
        15000);
  }

  private void stopScan() {
    scanning = false;
    if (scanner != null && scanCallback != null) {
      try {
        scanner.stopScan(scanCallback);
      } catch (SecurityException ignored) {
      }
    }
    scanner = null;
    scanCallback = null;
  }

  private void details() {
    if (service == null || service.controller() == null || !service.controller().idle()) return;
    column("details");
    title(service.deviceName());
    action(
        "Disconnect",
        () -> {
          if (service.controller() != null && service.controller().idle()) {
            service.disconnect();
            page = "";
            render();
          } else {
            page = "";
            render();
          }
        });
  }

  private void column(String name) {
    page = name;
    panel = null;
    ScrollView scroll = new ScrollView(this);
    scroll.setBackgroundColor(RemotePanel.BG);
    scroll.setFillViewport(true);
    list = new LinearLayout(this);
    list.setOrientation(LinearLayout.VERTICAL);
    list.setGravity(Gravity.CENTER_HORIZONTAL);
    list.setPadding(dp(34), dp(30), dp(34), dp(32));
    scroll.addView(list);
    setContentView(scroll);
    scroll.setOnGenericMotionListener(
        (v, event) -> {
          if (event.getAction() == android.view.MotionEvent.ACTION_SCROLL) {
            scroll.smoothScrollBy(
                0, Math.round(-event.getAxisValue(android.view.MotionEvent.AXIS_SCROLL) * dp(36)));
            return true;
          }
          return false;
        });
  }

  private void title(String label) {
    TextView title = RemotePanel.text(this, label, 18, RemotePanel.WHITE);
    title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    list.addView(
        title,
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
  }

  private TextView body(String label) {
    TextView text = RemotePanel.text(this, label, 12, RemotePanel.MUTED);
    text.setLineSpacing(dp(2), 1);
    LinearLayout.LayoutParams params =
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    params.topMargin = dp(10);
    params.bottomMargin = dp(8);
    list.addView(text, params);
    return text;
  }

  private Button action(String label, Runnable click) {
    Button button = RemotePanel.button(this, label, 0xff1d2722, RemotePanel.MINT);
    LinearLayout.LayoutParams params =
        new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
    params.topMargin = dp(8);
    list.addView(button, params);
    button.setOnClickListener(v -> click.run());
    return button;
  }

  private int dp(float value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  @Override
  public void onBackPressed() {
    if (page.equals("remote") || page.equals("home")) {
      super.onBackPressed();
      return;
    }
    stopScan();
    page = "";
    render();
  }
}
