// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Kahtaf

package dev.kahtaf.walkpad;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.wear.ongoing.OngoingActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Runs the actual Android controls through the test-only BLE transport. */
public final class SmokeInstrumentation extends Instrumentation {
  PreviewActivity activity;
  StringBuilder checks = new StringBuilder();

  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    start();
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    try {
      activity =
          (PreviewActivity)
              startActivitySync(
                  new Intent(getTargetContext(), PreviewActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      SystemClock.sleep(1200);
      waitForIdleSync();
      check(activity.controller.idle(), "Initial pad status is stopped");
      screenshot("01-ready");
      swipeUp();
      runOnMainSync(
          () ->
              check(
                  ((ScrollView) activity.findViewById(R.id.session_scroll)).getScrollY() > 0,
                  "Touch swipe scrolls the actual session page"));
      runOnMainSync(
          () -> {
            check(
                "84"
                    .contentEquals(
                        ((TextView) activity.findViewById(R.id.session_steps)).getText()),
                "Steps come from pad telemetry");
            check(
                "0.12 km"
                    .contentEquals(
                        ((TextView) activity.findViewById(R.id.session_distance)).getText()),
                "Distance is displayed in kilometres");
            check(
                "00:45"
                    .contentEquals(((TextView) activity.findViewById(R.id.session_time)).getText()),
                "Time comes from pad telemetry");
            ScrollView scroll = activity.findViewById(R.id.session_scroll);
            scroll.scrollTo(0, activity.panel.getHeight() - activity.panel.dp(40));
          });
      SystemClock.sleep(400);
      screenshot("08-session-stats");
      scroll(true);
      runOnMainSync(
          () -> {
            View button = activity.findViewById(R.id.mode_manual);
            int[] xy = new int[2];
            button.getLocationOnScreen(xy);
            View stop = activity.findViewById(R.id.stop);
            int[] stopXY = new int[2];
            stop.getLocationOnScreen(stopXY);
            check(
                xy[1] >= activity.panel.dp(24) && xy[1] + button.getHeight() <= stopXY[1],
                "Manual mode touch target fully above pinned Stop");
            View auto = activity.findViewById(R.id.mode_auto);
            Rect bounds = new Rect();
            check(
                auto.getGlobalVisibleRect(bounds) && bounds.height() == auto.getHeight(),
                "Auto mode touch target fully visible");
          });
      screenshot("09-mode-manual");
      check(
          activity.findViewById(R.id.mode_manual).isSelected(),
          "Mode reflects confirmed Manual telemetry");
      click(R.id.mode_auto);
      SystemClock.sleep(1200);
      check(
          activity.controller.status().mode() == 0
              && activity.findViewById(R.id.mode_auto).isSelected(),
          "Auto confirms from pad telemetry");
      check(activity.starts == 0, "Mode change does not send Start");
      screenshot("10-mode-auto");
      click(R.id.mode_manual);
      SystemClock.sleep(1200);
      check(activity.controller.status().mode() == 1, "Manual confirms from pad telemetry");
      scroll(false);
      click(R.id.start);
      check(activity.starts == 0, "First Start tap requires confirmation");
      screenshot("02-confirm");
      click(R.id.stop);
      SystemClock.sleep(1200);
      check(activity.starts == 0, "Stop cancels start confirmation");
      click(R.id.start);
      SystemClock.sleep(700);
      click(R.id.start);
      SystemClock.sleep(2200);
      check(activity.controller.moving(), "Confirmed Start receives moving telemetry");
      screenshot("03-walking");
      scroll(true);
      runOnMainSync(
          () -> {
            check(
                !activity.findViewById(R.id.mode_manual).isEnabled()
                    && !activity.findViewById(R.id.mode_auto).isEnabled(),
                "Mode controls disabled while moving");
          });
      screenshot("11-moving-mode");
      scroll(false);
      click(R.id.faster);
      SystemClock.sleep(700);
      check(activity.controller.status().speed() == 11, "Plus requests and confirms 1.1 km/h");
      click(R.id.slower);
      SystemClock.sleep(700);
      check(activity.controller.status().speed() == 10, "Minus requests and confirms 1.0 km/h");
      click(R.id.stop);
      SystemClock.sleep(1200);
      check(activity.controller.idle(), "Stop is confirmed by stopped pad telemetry");
      screenshot("04-stopped");
      click(R.id.start);
      SystemClock.sleep(700);
      click(R.id.start);
      SystemClock.sleep(2200);
      check(activity.starts == 2, "Start works again after standby");
      runOnMainSync(() -> activity.stale());
      SystemClock.sleep(5000);
      check(!activity.controller.fresh(), "Missing replies mark data stale");
      runOnMainSync(
          () -> {
            check(
                !activity.findViewById(R.id.faster).isEnabled(),
                "Stale data disables speed increase");
            check(
                activity.findViewById(R.id.stop).isEnabled(),
                "Stop remains available with stale data");
          });
      screenshot("05-stale");
      scroll(true);
      runOnMainSync(
          () -> {
            check(
                "—".contentEquals(((TextView) activity.findViewById(R.id.session_steps)).getText()),
                "Stale session data is unavailable rather than zero");
            check(
                !activity.findViewById(R.id.mode_manual).isEnabled()
                    && !activity.findViewById(R.id.mode_auto).isEnabled(),
                "Stale data disables mode changes");
          });
      screenshot("12-stale-session");
      runOnMainSync(() -> activity.finish());
      nativeBluetoothSmoke();
      result.putString("stream", "\n" + checks + "WATCH UI CHECKS PASSED\n");
      finish(Activity.RESULT_OK, result);
    } catch (Throwable error) {
      result.putString("stream", "\n" + checks + "FAILED: " + error + "\n");
      finish(Activity.RESULT_CANCELED, result);
    }
  }

  private void nativeBluetoothSmoke() throws Exception {
    String pkg = getTargetContext().getPackageName();
    getUiAutomation().grantRuntimePermission(pkg, android.Manifest.permission.BLUETOOTH_SCAN);
    getUiAutomation().grantRuntimePermission(pkg, android.Manifest.permission.BLUETOOTH_CONNECT);
    getUiAutomation().grantRuntimePermission(pkg, android.Manifest.permission.POST_NOTIFICATIONS);
    try (var descriptor = getUiAutomation().executeShellCommand("svc bluetooth enable")) {}
    MainActivity main =
        (MainActivity)
            startActivitySync(
                new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    SystemClock.sleep(1500);
    runOnMainSync(
        () -> {
          View find = main.findViewById(R.id.scan);
          check(find != null, "Real app home has Find pad");
          check(
              findText(main.getWindow().getDecorView(), "About & help") == null,
              "No About/help section");
          check(
              findText(main.getWindow().getDecorView(), "Your A1 Pro,\nfrom your wrist.") == null,
              "No marketing copy");
          find.performClick();
        });
    SystemClock.sleep(1000);
    runOnMainSync(
        () -> {
          check(
              findText(main.getWindow().getDecorView(), "Devices") != null,
              "Device picker opens directly without onboarding");
          main.onBackPressed();
        });
    CountDownLatch latch = new CountDownLatch(1);
    PadService[] services = new PadService[1];
    ServiceConnection binding =
        new ServiceConnection() {
          public void onServiceConnected(ComponentName name, IBinder binder) {
            services[0] = ((PadService.LocalBinder) binder).service();
            latch.countDown();
          }

          public void onServiceDisconnected(ComponentName name) {}
        };
    runOnMainSync(
        () ->
            main.bindService(
                new Intent(main, PadService.class),
                binding,
                android.content.Context.BIND_AUTO_CREATE));
    check(latch.await(5, TimeUnit.SECONDS), "Real Bluetooth service binds");
    runOnMainSync(
        () ->
            main.startForegroundService(
                new Intent(main, PadService.class)
                    .setAction(PadService.CONNECT)
                    .putExtra("address", "AA:BB:CC:DD:EE:FF")
                    .putExtra("name", "Emulator test only")));
    SystemClock.sleep(1200);
    runOnMainSync(
        () -> {
          check(services[0].connecting(), "Native GATT connection starts in foreground service");
          check(
              services[0].controller() == null, "No telemetry means no motor controls are exposed");
        });
    StatusBarNotification posted = waitForNotification(true);
    check(
        (posted.getNotification().flags & Notification.FLAG_ONGOING_EVENT) != 0,
        "Connection notification is ongoing");
    check(
        (posted.getNotification().flags & Notification.FLAG_FOREGROUND_SERVICE) != 0,
        "Notification belongs to the connection foreground service");
    OngoingActivity ongoing = OngoingActivity.recoverOngoingActivity(main);
    check(ongoing != null, "Wear OS ongoing-activity metadata is present");
    check(
        ongoing.getStaticIcon() != null && ongoing.getTouchIntent().isImmutable(),
        "Watch-face shortcut has an icon and immutable return intent");
    getUiAutomation().performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
    SystemClock.sleep(600);
    waitForNotification(true);
    check(services[0].hasLink(), "Connection and notification remain after leaving app");
    capture("06-ongoing-watchface");
    ongoing.getTouchIntent().send();
    long returnDeadline = SystemClock.elapsedRealtime() + 3000;
    boolean returned = false;
    while (SystemClock.elapsedRealtime() < returnDeadline) {
      var root = getUiAutomation().getRootInActiveWindow();
      if (root != null && pkg.contentEquals(root.getPackageName())) {
        returned = true;
        break;
      }
      SystemClock.sleep(100);
    }
    check(returned, "Ongoing-activity tap returns to Stride Remote");
    capture("07-ongoing-return");
    runOnMainSync(
        () -> {
          services[0].disconnect();
          check(!services[0].hasLink(), "Cancel closes native GATT connection");
        });
    waitForNotification(false);
    check(
        OngoingActivity.recoverOngoingActivity(main) == null,
        "Disconnect removes watch-face shortcut");
    notificationUpdateSmoke(main);
    runOnMainSync(
        () -> {
          main.unbindService(binding);
          main.finish();
        });
  }

  @android.annotation.SuppressLint("MissingPermission")
  private void notificationUpdateSmoke(MainActivity main) {
    // Runtime notification permission was granted above by UiAutomation.
    NotificationManager manager = main.getSystemService(NotificationManager.class);
    PadNotification notification = new PadNotification(main);
    manager.notify(PadNotification.ID, notification.start("Simulated 1.0 km/h"));
    waitForNotification(true);
    notification.update("Simulated 2.0 km/h");
    long deadline = SystemClock.elapsedRealtime() + 3000;
    boolean updated = false;
    while (SystemClock.elapsedRealtime() < deadline) {
      StatusBarNotification item = waitForNotification(true);
      if ("Simulated 2.0 km/h"
          .contentEquals(item.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT))) {
        updated = true;
        break;
      }
      SystemClock.sleep(100);
    }
    check(updated, "Live speed updates the existing notification");
    OngoingActivity current = OngoingActivity.recoverOngoingActivity(main);
    check(
        current != null
            && "Simulated 2.0 km/h"
                .contentEquals(current.getStatus().getText(main, SystemClock.elapsedRealtime())),
        "Watch-face activity status follows speed updates");
    check(
        java.util.Arrays.stream(manager.getActiveNotifications())
                .filter(n -> n.getId() == PadNotification.ID)
                .count()
            == 1,
        "Speed updates do not duplicate notifications");
    notification.clear();
    waitForNotification(false);
  }

  private StatusBarNotification waitForNotification(boolean present) {
    NotificationManager manager = getTargetContext().getSystemService(NotificationManager.class);
    long deadline = SystemClock.elapsedRealtime() + 3000;
    do {
      StatusBarNotification found =
          java.util.Arrays.stream(manager.getActiveNotifications())
              .filter(n -> n.getId() == PadNotification.ID)
              .findFirst()
              .orElse(null);
      if ((found != null) == present) return found;
      SystemClock.sleep(100);
    } while (SystemClock.elapsedRealtime() < deadline);
    throw new AssertionError(
        present
            ? "Connection notification missing"
            : "Connection notification remained after disconnect");
  }

  private View findText(View view, String text) {
    if (view instanceof TextView tv && text.contentEquals(tv.getText())) return view;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = findText(group.getChildAt(i), text);
        if (found != null) return found;
      }
    return null;
  }

  private void swipeUp() {
    long down = SystemClock.uptimeMillis();
    float x = activity.panel.getWidth() / 2f,
        from = activity.panel.dp(138),
        to = activity.panel.dp(40);
    pointer(down, MotionEvent.ACTION_DOWN, x, from);
    for (int i = 1; i <= 10; i++) {
      SystemClock.sleep(30);
      pointer(down, MotionEvent.ACTION_MOVE, x, from + (to - from) * i / 10);
    }
    pointer(down, MotionEvent.ACTION_UP, x, to);
    SystemClock.sleep(500);
    waitForIdleSync();
  }

  private void pointer(long down, int action, float x, float y) {
    MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
    try {
      sendPointerSync(event);
    } finally {
      event.recycle();
    }
  }

  private void scroll(boolean down) {
    runOnMainSync(
        () -> {
          ScrollView v = activity.findViewById(R.id.session_scroll);
          v.scrollTo(0, down ? v.getChildAt(0).getHeight() : 0);
        });
    SystemClock.sleep(500);
    waitForIdleSync();
  }

  private void click(int id) {
    runOnMainSync(
        () -> {
          View button = activity.findViewById(id);
          check(button.isEnabled(), "Control enabled: " + id);
          button.performClick();
        });
    waitForIdleSync();
  }

  private void check(boolean condition, String text) {
    if (!condition) throw new AssertionError(text);
    checks.append("PASS ").append(text).append('\n');
  }

  private void screenshot(String name) throws Exception {
    waitForIdleSync();
    runOnMainSync(
        () -> {
          View stop = activity.findViewById(R.id.stop);
          Rect visible = new Rect();
          check(
              stop.getGlobalVisibleRect(visible) && visible.height() == stop.getHeight(),
              "Stop fully visible: " + name);
          check(
              stop.getHeight() / activity.getResources().getDisplayMetrics().density >= 47.5,
              "Stop touch target >=48dp");
        });
    capture(name);
  }

  private void capture(String name) throws Exception {
    Bitmap bitmap = getUiAutomation().takeScreenshot();
    File directory = new File(getTargetContext().getExternalFilesDir(null), "screenshots");
    if (!directory.exists() && !directory.mkdirs())
      throw new IllegalStateException("Cannot create screenshot folder");
    try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
    }
    bitmap.recycle();
  }
}
