// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Kahtaf

package dev.kahtaf.walkpad;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.WindowInsets;
import android.widget.FrameLayout;

/** Test-only transport. This Activity and simulated pad are absent from release APKs. */
public final class PreviewActivity extends Activity {
  final Handler handler = new Handler(Looper.getMainLooper());
  public PadController controller;
  public RemotePanel panel;
  public int starts;
  int speed, mode = 2, state = 2;
  boolean replies = true;

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    getWindow().getDecorView().getWindowInsetsController().hide(WindowInsets.Type.systemBars());
    FrameLayout root = new FrameLayout(this);
    root.setBackgroundColor(RemotePanel.BG);
    panel = new RemotePanel(this, () -> {});
    root.addView(panel, new FrameLayout.LayoutParams(-1, -1));
    android.widget.TextView simulation =
        RemotePanel.text(this, "SIMULATED PAD", 8, RemotePanel.MUTED);
    FrameLayout.LayoutParams simulationParams =
        new FrameLayout.LayoutParams(-1, panel.dp(14), Gravity.BOTTOM);
    simulationParams.bottomMargin = panel.dp(3);
    root.addView(simulation, simulationParams);
    setContentView(root);
    CommandQueue.Scheduler scheduler =
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
            scheduler,
            (frame, done) -> {
              int code = frame[2] & 255, value = frame[3] & 255;
              if (replies) {
                if (code == 1) {
                  speed = value;
                  state = speed > 0 ? 1 : 0;
                }
                if (code == 2) {
                  mode = value;
                  if (mode == 2) {
                    speed = 0;
                    state = 2;
                  } else state = speed > 0 ? 1 : 0;
                }
                if (code == 4) {
                  starts++;
                  speed = 10;
                  state = 1;
                }
              }
              handler.postDelayed(
                  () -> {
                    done.accept(null);
                    if (replies) controller.receive(sample());
                  },
                  20);
            },
            new PadController.Observer() {
              public void changed() {
                panel.render(controller);
              }

              public void fatal(String reason) {
                throw new IllegalStateException(reason);
              }
            });
    controller.prepareManualOnConnection();
    controller.begin();
  }

  private byte[] sample() {
    return new byte[] {
      (byte) 0xf8,
      (byte) 0xa2,
      (byte) state,
      (byte) speed,
      (byte) mode,
      0,
      0,
      45,
      0,
      0,
      12,
      0,
      0,
      84,
      30,
      0,
      0,
      (byte) 0xfd
    };
  }

  public void stale() {
    replies = false;
  }

  @Override
  protected void onPause() {
    panel.cancelConfirmation();
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    controller.close();
    handler.removeCallbacksAndMessages(null);
    super.onDestroy();
  }
}
