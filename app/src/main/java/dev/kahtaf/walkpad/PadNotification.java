// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Kahtaf

package dev.kahtaf.walkpad;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import androidx.core.app.NotificationCompat;
import androidx.wear.ongoing.OngoingActivity;
import androidx.wear.ongoing.Status;

/** One notification for the lifetime of a pad connection, including watch-face return. */
public final class PadNotification {
  public static final int ID = 1;
  private final Context context;
  private final NotificationManager manager;
  private NotificationCompat.Builder builder;
  private OngoingActivity activity;
  private String lastText;

  public PadNotification(Context context) {
    this.context = context.getApplicationContext();
    this.manager = context.getSystemService(NotificationManager.class);
  }

  public Notification start(String text) {
    manager.createNotificationChannel(
        new NotificationChannel("pad", "Pad connection", NotificationManager.IMPORTANCE_LOW));
    Intent returnIntent =
        new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
    PendingIntent open =
        PendingIntent.getActivity(
            context,
            1,
            returnIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    PendingIntent stop =
        PendingIntent.getService(
            context,
            2,
            new Intent(context, PadService.class).setAction(PadService.STOP),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    builder =
        new NotificationCompat.Builder(context, "pad")
            .setSmallIcon(R.drawable.ic_ongoing)
            .setContentTitle("Stride Remote")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setLocalOnly(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Stop", stop);
    activity =
        new OngoingActivity.Builder(context, ID, builder)
            .setStaticIcon(R.drawable.ic_ongoing)
            .setTouchIntent(open)
            .setStatus(status(text))
            .build();
    activity.apply(context);
    lastText = text;
    return builder.build();
  }

  public void update(String text) {
    if (activity == null || text.equals(lastText)) return;
    builder.setContentText(text);
    if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        || !manager.areNotificationsEnabled()) return;
    activity.update(context, status(text));
    lastText = text;
  }

  public void clear() {
    manager.cancel(ID);
    activity = null;
    builder = null;
    lastText = null;
  }

  private static Status status(String text) {
    return Status.forPart(new Status.TextPart(text));
  }
}
