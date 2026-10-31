// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Kahtaf

package dev.kahtaf.walkpad;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Locale;

/** Native controls with a persistent Stop target and scrollable session data. */
public final class RemotePanel extends FrameLayout {
  public static final int BG = Color.rgb(8, 12, 11),
      MINT = Color.rgb(186, 245, 206),
      WHITE = Color.rgb(241, 246, 243),
      MUTED = Color.rgb(151, 169, 160),
      RED = Color.rgb(255, 190, 181);
  private static final int BUTTON = Color.rgb(29, 39, 34);
  private final TextView status, speed, steps, distance, time, mode;
  private final Button minus, start, plus, stop, manual, auto;
  private final LinearLayout content;
  private final FrameLayout firstPage;
  private final LinearLayout controlsPage;
  private final boolean compact;
  private final View stopBackdrop;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private PadController controller;
  private long armedAt = -1;

  public RemotePanel(Context context) {
    this(context, () -> {});
  }

  public RemotePanel(Context context, Runnable details) {
    super(context);
    compact = getResources().getConfiguration().screenHeightDp < 210;
    setBackgroundColor(BG);
    ScrollView scroll = new ScrollView(context);
    scroll.setId(R.id.session_scroll);
    scroll.setVerticalScrollBarEnabled(false);
    scroll.setClipToPadding(false);
    scroll.setFocusableInTouchMode(true);
    scroll.setOnGenericMotionListener(
        (v, event) -> {
          if (event.getAction() != MotionEvent.ACTION_SCROLL
              || !event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) return false;
          scroll.smoothScrollBy(
              0, Math.round(-event.getAxisValue(MotionEvent.AXIS_SCROLL) * dp(36)));
          return true;
        });
    addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    content = new LinearLayout(context);
    content.setOrientation(LinearLayout.VERTICAL);
    scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
    firstPage = new FrameLayout(context);
    content.addView(firstPage, new LinearLayout.LayoutParams(-1, dp(219)));
    controlsPage = new LinearLayout(context);
    controlsPage.setOrientation(LinearLayout.VERTICAL);
    controlsPage.setGravity(Gravity.CENTER_HORIZONTAL);
    firstPage.addView(controlsPage, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
    status = text(context, "READING PAD", 11, MUTED);
    status.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    controlsPage.addView(status, new LinearLayout.LayoutParams(-1, dp(compact ? 18 : 20)));
    speed = text(context, "—", 38, WHITE);
    speed.setFontFeatureSettings("tnum");
    speed.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    speed.setId(R.id.speed);
    speed.setOnLongClickListener(
        v -> {
          if (controller != null && controller.idle()) details.run();
          return true;
        });
    speed.setContentDescription("Speed. Long press when stopped to disconnect.");
    speed.setAutoSizeTextTypeUniformWithConfiguration(
        24, 38, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
    controlsPage.addView(speed, new LinearLayout.LayoutParams(-1, dp(compact ? 40 : 48)));
    controlsPage.addView(
        text(context, "km/h", 11, MUTED), new LinearLayout.LayoutParams(-1, dp(compact ? 14 : 16)));
    LinearLayout controls = new LinearLayout(context);
    controls.setGravity(Gravity.CENTER);
    minus = button(context, "−", BUTTON, WHITE);
    minus.setTextSize(25);
    minus.setId(R.id.slower);
    minus.setContentDescription("Decrease speed by 0.1 kilometres per hour");
    plus = button(context, "+", BUTTON, WHITE);
    plus.setTextSize(25);
    plus.setId(R.id.faster);
    plus.setContentDescription("Increase speed by 0.1 kilometres per hour");
    start = button(context, "START", MINT, BG);
    start.setTextSize(10);
    start.setId(R.id.start);
    controls.addView(minus, new LinearLayout.LayoutParams(dp(48), dp(48)));
    LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(dp(64), dp(48));
    startParams.setMargins(dp(4), 0, dp(4), 0);
    controls.addView(start, startParams);
    controls.addView(plus, new LinearLayout.LayoutParams(dp(48), dp(48)));
    LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(-1, dp(compact ? 48 : 52));
    row.topMargin = dp(2);
    controlsPage.addView(controls, row);
    LinearLayout session = new LinearLayout(context);
    session.setOrientation(LinearLayout.VERTICAL);
    session.setPadding(dp(34), dp(4), dp(34), dp(12));
    content.addView(session, new LinearLayout.LayoutParams(-1, -2));
    steps = metric(session, "Steps", R.id.session_steps);
    distance = metric(session, "Distance", R.id.session_distance);
    time = metric(session, "Time", R.id.session_time);
    mode = text(context, "Mode", 11, MUTED);
    LinearLayout.LayoutParams modeLabel = new LinearLayout.LayoutParams(-1, dp(28));
    modeLabel.topMargin = dp(8);
    session.addView(mode, modeLabel);
    LinearLayout modes = new LinearLayout(context);
    modes.setGravity(Gravity.CENTER);
    manual = button(context, "Manual", BUTTON, WHITE);
    manual.setId(R.id.mode_manual);
    auto = button(context, "Auto", BUTTON, WHITE);
    auto.setId(R.id.mode_auto);
    LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, dp(48), 1);
    left.rightMargin = dp(4);
    modes.addView(manual, left);
    LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, dp(48), 1);
    right.leftMargin = dp(4);
    modes.addView(auto, right);
    session.addView(modes, new LinearLayout.LayoutParams(-1, dp(48)));
    stopBackdrop = new View(context);
    stopBackdrop.setBackgroundColor(BG);
    stopBackdrop.setClickable(true);
    stopBackdrop.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    addView(stopBackdrop, new FrameLayout.LayoutParams(-1, dp(76), Gravity.BOTTOM));
    stop = button(context, "■  STOP", Color.rgb(74, 30, 29), RED);
    stop.setId(R.id.stop);
    stop.setTextSize(13);
    stop.setContentDescription("Stop the walking pad");
    addView(
        stop,
        new FrameLayout.LayoutParams(
            dp(compact ? 120 : 128), dp(48), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
    minus.setOnClickListener(v -> adjust(-1));
    plus.setOnClickListener(v -> adjust(1));
    manual.setOnClickListener(v -> chooseMode(1));
    auto.setOnClickListener(v -> chooseMode(0));
    start.setOnClickListener(
        v -> {
          if (controller == null || !controller.idle()) return;
          haptic(v);
          if (armedAt < 0) {
            armedAt = SystemClock.elapsedRealtime();
            long armed = armedAt;
            handler.postDelayed(
                () -> {
                  if (armedAt == armed) {
                    armedAt = -1;
                    render(controller);
                  }
                },
                8000);
          } else if (SystemClock.elapsedRealtime() - armedAt >= 600) {
            armedAt = -1;
            controller.start();
          }
          render(controller);
        });
    stop.setOnClickListener(
        v -> {
          haptic(v);
          armedAt = -1;
          if (controller != null) controller.stop();
          render(controller);
        });
    scroll.requestFocus();
    render(null);
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    int h = MeasureSpec.getSize(heightMeasureSpec);
    int edge = Math.max(dp(8), (h - dp(compact ? 174 : 190)) / 2);
    if (firstPage.getLayoutParams().height != h) {
      firstPage.getLayoutParams().height = h;
      firstPage.requestLayout();
    }
    FrameLayout.LayoutParams top = (FrameLayout.LayoutParams) controlsPage.getLayoutParams();
    if (top.topMargin != edge) {
      top.topMargin = edge;
      controlsPage.setLayoutParams(top);
    }
    FrameLayout.LayoutParams bottom = (FrameLayout.LayoutParams) stop.getLayoutParams();
    if (bottom.bottomMargin != edge) {
      bottom.bottomMargin = edge;
      stop.setLayoutParams(bottom);
    }
    FrameLayout.LayoutParams backdrop = (FrameLayout.LayoutParams) stopBackdrop.getLayoutParams();
    if (backdrop.height != edge + dp(48)) {
      backdrop.height = edge + dp(48);
      stopBackdrop.setLayoutParams(backdrop);
    }
    content.setPadding(0, 0, 0, edge + dp(60));
    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
  }

  private TextView metric(LinearLayout parent, String label, int id) {
    LinearLayout row = new LinearLayout(getContext());
    row.setGravity(Gravity.CENTER_VERTICAL);
    TextView name = text(getContext(), label, 11, MUTED);
    name.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    row.addView(name, new LinearLayout.LayoutParams(0, dp(36), 1));
    TextView value = text(getContext(), "—", 18, WHITE);
    value.setId(id);
    value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    value.setSingleLine(true);
    value.setFontFeatureSettings("tnum");
    value.setAutoSizeTextTypeUniformWithConfiguration(
        12, 18, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
    row.addView(value, new LinearLayout.LayoutParams(0, dp(36), 1.5f));
    parent.addView(row, new LinearLayout.LayoutParams(-1, dp(36)));
    return value;
  }

  private void chooseMode(int value) {
    armedAt = -1;
    if (controller != null) controller.setMode(value);
    haptic(this);
    render(controller);
  }

  private void adjust(int delta) {
    armedAt = -1;
    if (controller != null) controller.adjust(delta);
    haptic(this);
    render(controller);
  }

  public void cancelConfirmation() {
    armedAt = -1;
    render(controller);
  }

  public void render(PadController next) {
    controller = next;
    boolean fresh = next != null && next.fresh(), moving = next != null && next.moving();
    if (next == null || !next.idle()) armedAt = -1;
    speed.setText(fresh ? format(moving ? next.status().speed() : next.target()) : "—");
    speed.setTextColor(fresh ? moving ? WHITE : MINT : MUTED);
    speed.setContentDescription(
        fresh
            ? format(moving ? next.status().speed() : next.target())
                + " kilometres per hour. Long press when stopped to disconnect."
            : "Speed unavailable");
    String label;
    if (!fresh) label = "NO LIVE DATA";
    else if (armedAt >= 0) label = "Start at " + format(next.target()) + " km/h?";
    else
      label =
          switch (next.pending()) {
            case MODE -> "SWITCHING…";
            case START -> "STARTING…";
            case SPEED -> "SETTING…";
            case STOP -> "STOPPING…";
            case FAILED -> "CHECK BELT";
            case NONE ->
                next.status().mode() == 0
                    ? "AUTO"
                    : moving ? "WALKING" : next.idle() ? "READY" : "WAIT";
          };
    status.setText(label);
    status.setTextColor(next != null && next.uncertain() ? RED : MUTED);
    start.setText(armedAt >= 0 ? "GO" : "START");
    start.setContentDescription(
        armedAt >= 0
            ? "Confirm start at " + format(next.target()) + " kilometres per hour"
            : "Prepare to start walking pad");
    start.setEnabled(next != null && next.idle());
    minus.setEnabled(next != null && next.canAdjust() && next.target() > ClassicProtocol.MIN_SPEED);
    plus.setEnabled(next != null && next.canAdjust() && next.target() < ClassicProtocol.MAX_SPEED);
    stop.setEnabled(next != null);
    steps.setText(fresh ? String.format(Locale.getDefault(), "%,d", next.status().steps()) : "—");
    distance.setText(
        fresh
            ? String.format(
                Locale.getDefault(), "%.2f km", next.status().distanceHundredths() / 100.0)
            : "—");
    time.setText(fresh ? elapsed(next.status().seconds()) : "—");
    mode.setText(
        !fresh
            ? "Mode"
            : moving
                ? "Mode · stop first"
                : next.pending() == PadController.Pending.MODE
                    ? "Mode · switching"
                    : next.status().mode() == 2 ? "Mode · standby" : "Mode");
    modeButton(
        manual, fresh && next.status().mode() == 1, next != null && next.canChangeMode(), "Manual");
    modeButton(
        auto, fresh && next.status().mode() == 0, next != null && next.canChangeMode(), "Auto");
    for (Button button : new Button[] {minus, plus, start})
      button.setAlpha(button.isEnabled() ? 1f : 0.35f);
  }

  private void modeButton(Button button, boolean selected, boolean enabled, String label) {
    button.setSelected(selected);
    button.setEnabled(enabled);
    button.setAlpha(enabled ? 1f : 0.5f);
    ((GradientDrawable)
            ((android.graphics.drawable.RippleDrawable) button.getBackground()).getDrawable(0))
        .setColor(selected ? MINT : BUTTON);
    button.setTextColor(selected ? BG : WHITE);
    button.setContentDescription(label + (selected ? ", selected" : ""));
  }

  private static String elapsed(int seconds) {
    return seconds >= 3600
        ? String.format(
            Locale.getDefault(), "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        : String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60);
  }

  public static TextView text(Context context, String value, float size, int color) {
    TextView view = new TextView(context);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setGravity(Gravity.CENTER);
    view.setIncludeFontPadding(false);
    return view;
  }

  public static Button button(Context context, String label, int fill, int color) {
    Button button = new Button(context);
    button.setText(label);
    button.setTextColor(color);
    button.setTextSize(12);
    button.setAllCaps(false);
    button.setMinHeight(0);
    button.setMinimumHeight(0);
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    button.setPadding(0, 0, 0, 0);
    button.setStateListAnimator(null);
    button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    GradientDrawable shape = new GradientDrawable();
    shape.setColor(fill);
    shape.setCornerRadius(100);
    button.setBackground(
        new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x30ffffff), shape, null));
    return button;
  }

  private void haptic(View view) {
    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
  }

  public int dp(float value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private static String format(int tenths) {
    return String.format(Locale.getDefault(), "%.1f", tenths / 10.0);
  }

  @Override
  protected void onDetachedFromWindow() {
    handler.removeCallbacksAndMessages(null);
    super.onDetachedFromWindow();
  }
}
