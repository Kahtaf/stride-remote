// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Kahtaf

package dev.kahtaf.walkpad;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Owns the connection across Activity recreation and screen-off; all state lives on main. */
@SuppressLint("MissingPermission")
public class PadService extends Service {
  public static final String CONNECT = "dev.kahtaf.walkpad.CONNECT",
      STOP = "dev.kahtaf.walkpad.STOP";
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final IBinder binder = new LocalBinder();
  private final List<Runnable> observers = new ArrayList<>();
  private final ArrayDeque<String> log = new ArrayDeque<>();
  private BluetoothGatt gatt;
  private BluetoothGattCharacteristic writer;
  private Consumer<String> writeCompletion;
  private PadController controller;
  private String message = "", deviceName = "WalkingPad", address;
  private boolean connecting, foreground, everMoving, saved, disconnectAfterStop;
  private long connectionGeneration;
  private PadNotification notification;
  private PowerManager.WakeLock commandWakeLock;

  @Override
  public void onCreate() {
    super.onCreate();
    notification = new PadNotification(this);
  }

  public class LocalBinder extends Binder {
    public PadService service() {
      return PadService.this;
    }
  }

  @Override
  public IBinder onBind(Intent intent) {
    return binder;
  }

  public void observe(Runnable listener) {
    observers.add(listener);
  }

  public void unobserve(Runnable listener) {
    observers.remove(listener);
  }

  public PadController controller() {
    return controller;
  }

  public String message() {
    return message;
  }

  public String deviceName() {
    return deviceName;
  }

  public boolean connecting() {
    return connecting;
  }

  public boolean hasLink() {
    return controller != null || connecting;
  }

  public boolean beltMayMove() {
    return everMoving || controller != null && !controller.idle();
  }

  public String diagnosticText() {
    return "Stride Remote "
        + BuildConfig.VERSION_NAME
        + "\nClassic FE00 · "
        + deviceName
        + "\n"
        + message
        + (controller == null
            ? ""
            : "\nstatus="
                + controller.status()
                + " pending="
                + controller.pending()
                + " fresh="
                + controller.fresh())
        + "\n\n"
        + String.join("\n", log);
  }

  @Override
  protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
    writer.println(diagnosticText());
  }

  private boolean bluetoothGranted() {
    return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
        == PackageManager.PERMISSION_GRANTED;
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null) return START_NOT_STICKY;
    if (!bluetoothGranted()) {
      fail("Bluetooth permission needed");
      return START_NOT_STICKY;
    }
    if (CONNECT.equals(intent.getAction())) {
      startNotice("Connecting…");
      connect(intent.getStringExtra("address"), intent.getStringExtra("name"));
    } else if (STOP.equals(intent.getAction()) && controller != null) controller.stop();
    return START_NOT_STICKY;
  }

  private void connect(String selectedAddress, String selectedName) {
    if (hasLink()) return;
    connectionGeneration++;
    long generation = connectionGeneration;
    connecting = true;
    saved = false;
    disconnectAfterStop = false;
    address = selectedAddress;
    deviceName = selectedName == null || selectedName.isBlank() ? "WalkingPad" : selectedName;
    message = "Connecting…";
    changed();
    try {
      BluetoothManager manager = getSystemService(BluetoothManager.class);
      if (manager == null || manager.getAdapter() == null || !manager.getAdapter().isEnabled()) {
        fail("Turn on watch Bluetooth");
        return;
      }
      BluetoothDevice device = manager.getAdapter().getRemoteDevice(address);
      gatt = device.connectGatt(this, false, callbacks, BluetoothDevice.TRANSPORT_LE);
      if (gatt == null) {
        fail("Could not open Bluetooth");
        return;
      }
      handler.postDelayed(
          () -> {
            if (generation == connectionGeneration && connecting) fail("Connection timed out");
          },
          20000);
    } catch (IllegalArgumentException | SecurityException e) {
      fail("Cannot connect: " + e.getMessage());
    }
  }

  private final BluetoothGattCallback callbacks =
      new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt link, int status, int state) {
          handler.post(
              () -> {
                if (link != gatt) return;
                try {
                  if (status != BluetoothGatt.GATT_SUCCESS
                      || state == BluetoothProfile.STATE_DISCONNECTED) {
                    fail("Disconnected · check belt");
                    return;
                  }
                  if (state == BluetoothProfile.STATE_CONNECTED) {
                    message = "Checking pad…";
                    changed();
                    if (!link.discoverServices()) fail("Could not discover pad services");
                  }
                } catch (SecurityException e) {
                  fail("Bluetooth permission removed");
                }
              });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt link, int status) {
          handler.post(
              () -> {
                if (link != gatt) return;
                try {
                  if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("Service discovery failed: " + status);
                    return;
                  }
                  BluetoothGattService service = link.getService(ClassicProtocol.SERVICE);
                  if (service == null) {
                    fail("Unsupported device");
                    return;
                  }
                  writer = service.getCharacteristic(ClassicProtocol.WRITE);
                  BluetoothGattCharacteristic notifier =
                      service.getCharacteristic(ClassicProtocol.NOTIFY);
                  if (writer == null
                      || notifier == null
                      || (writer.getProperties()
                              & (BluetoothGattCharacteristic.PROPERTY_WRITE
                                  | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE))
                          == 0) {
                    fail("Pad control characteristics are missing");
                    return;
                  }
                  BluetoothGattDescriptor descriptor = notifier.getDescriptor(ClassicProtocol.CCCD);
                  if (descriptor == null || !link.setCharacteristicNotification(notifier, true)) {
                    fail("Cannot subscribe to pad data");
                    return;
                  }
                  byte[] subscription =
                      (notifier.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                          ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                          : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
                  boolean accepted =
                      link.writeDescriptor(descriptor, subscription)
                          == BluetoothStatusCodes.SUCCESS;
                  if (!accepted) fail("Cannot enable pad notifications");
                } catch (SecurityException e) {
                  fail("Bluetooth permission removed");
                }
              });
        }

        @Override
        public void onDescriptorWrite(
            BluetoothGatt link, BluetoothGattDescriptor descriptor, int status) {
          handler.post(
              () -> {
                if (link != gatt || !ClassicProtocol.CCCD.equals(descriptor.getUuid())) return;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                  fail("Notification subscription failed: " + status);
                  return;
                }
                connecting = false;
                message = "Reading pad…";
                CommandQueue.Scheduler clock =
                    new CommandQueue.Scheduler() {
                      public long now() {
                        return SystemClock.elapsedRealtime();
                      }

                      public void after(long delay, Runnable task) {
                        handler.postDelayed(task, delay);
                      }
                    };
                controller =
                    new PadController(
                        clock,
                        PadService.this::write,
                        new PadController.Observer() {
                          public void changed() {
                            if (controller == null) return;
                            if (disconnectAfterStop && controller.standby()) {
                              finishDisconnect();
                              return;
                            }
                            if (disconnectAfterStop
                                && controller.pending() == PadController.Pending.FAILED)
                              disconnectAfterStop = false;
                            if (controller.fresh()) {
                              if (!saved) {
                                getSharedPreferences("pad", MODE_PRIVATE)
                                    .edit()
                                    .putString("address", address)
                                    .putString("name", deviceName)
                                    .apply();
                                saved = true;
                              }
                              everMoving =
                                  controller.moving()
                                      || controller.pending() != PadController.Pending.NONE;
                            }
                            message = controller.problem();
                            PadService.this.changed();
                          }

                          public void fatal(String reason) {
                            fail(reason);
                          }
                        });
                controller.prepareManualOnConnection();
                controller.begin();
              });
        }

        @Override
        public void onCharacteristicWrite(
            BluetoothGatt link, BluetoothGattCharacteristic characteristic, int status) {
          handler.post(
              () -> {
                if (link != gatt || !ClassicProtocol.WRITE.equals(characteristic.getUuid())) return;
                Consumer<String> completion = writeCompletion;
                writeCompletion = null;
                if (completion != null)
                  completion.accept(
                      status == BluetoothGatt.GATT_SUCCESS ? null : "Pad write failed: " + status);
              });
        }

        @Override
        public void onCharacteristicChanged(
            BluetoothGatt link, BluetoothGattCharacteristic characteristic, byte[] value) {
          receive(link, characteristic, value.clone());
        }

        @Override
        public void onCharacteristicChanged(
            BluetoothGatt link, BluetoothGattCharacteristic characteristic) {
          byte[] value = characteristic.getValue();
          if (value != null) receive(link, characteristic, value.clone());
        }
      };

  private void receive(
      BluetoothGatt link, BluetoothGattCharacteristic characteristic, byte[] value) {
    handler.post(
        () -> {
          if (link != gatt
              || controller == null
              || !ClassicProtocol.NOTIFY.equals(characteristic.getUuid())) return;
          record("rx " + hex(value));
          controller.receive(value);
        });
  }

  private void write(byte[] value, Consumer<String> completion) {
    if (gatt == null || writer == null) {
      completion.accept("Bluetooth link is closed");
      return;
    }
    try {
      int type =
          (writer.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
              ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
              : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
      writeCompletion = completion;
      record("tx " + hex(value));
      boolean accepted =
          gatt.writeCharacteristic(writer, value, type) == BluetoothStatusCodes.SUCCESS;
      if (!accepted) {
        writeCompletion = null;
        completion.accept("Write failed · reconnect");
      }
    } catch (SecurityException e) {
      writeCompletion = null;
      completion.accept("Bluetooth permission removed");
    }
  }

  public void disconnect() {
    if (controller == null) {
      finishDisconnect();
      return;
    }
    disconnectAfterStop = true;
    controller.stop();
  }

  private void finishDisconnect() {
    shutdown();
    message = "Disconnected";
    everMoving = false;
    changed();
  }

  private void fail(String reason) {
    record(reason);
    shutdown();
    message = reason;
    changed();
  }

  private void shutdown() {
    connectionGeneration++;
    connecting = false;
    disconnectAfterStop = false;
    if (commandWakeLock != null && commandWakeLock.isHeld()) commandWakeLock.release();
    if (controller != null) controller.close();
    controller = null;
    writer = null;
    writeCompletion = null;
    BluetoothGatt old = gatt;
    gatt = null;
    if (old != null) {
      try {
        old.disconnect();
        old.close();
      } catch (SecurityException ignored) {
      }
    }
    if (foreground) {
      stopForeground(STOP_FOREGROUND_REMOVE);
      foreground = false;
    }
    notification.clear();
    stopSelf();
  }

  private void changed() {
    boolean commandActive =
        controller != null
            && (controller.pending() == PadController.Pending.MODE
                || controller.pending() == PadController.Pending.START
                || controller.pending() == PadController.Pending.SPEED
                || controller.pending() == PadController.Pending.STOP);
    if (commandActive) {
      if (commandWakeLock == null) {
        commandWakeLock =
            getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StrideRemote:command");
        commandWakeLock.setReferenceCounted(false);
      }
      if (!commandWakeLock.isHeld()) commandWakeLock.acquire(20000);
    } else if (commandWakeLock != null && commandWakeLock.isHeld()) commandWakeLock.release();
    for (Runnable observer : List.copyOf(observers)) observer.run();
    if (foreground) {
      String text =
          controller == null
              ? message
              : !controller.fresh()
                  ? "No data · check belt"
                  : String.format(
                      Locale.ROOT,
                      "%.1f km/h · %s",
                      controller.status().speed() / 10.0,
                      controller.pending() == PadController.Pending.NONE
                          ? "Connected"
                          : controller.pending().name());
      notification.update(text);
    }
  }

  private void startNotice(String text) {
    startForeground(
        PadNotification.ID,
        notification.start(text),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
    foreground = true;
  }

  private void record(String text) {
    if (log.size() >= 100) log.removeFirst();
    log.addLast((SystemClock.elapsedRealtime() / 1000) + " " + text);
  }

  private static String hex(byte[] bytes) {
    StringBuilder text = new StringBuilder();
    for (byte b : bytes) text.append(String.format(Locale.ROOT, "%02X ", b & 255));
    return text.toString().trim();
  }

  @Override
  public void onDestroy() {
    shutdown();
    handler.removeCallbacksAndMessages(null);
    super.onDestroy();
  }
}
