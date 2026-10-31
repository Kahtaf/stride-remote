// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Kahtaf

package dev.kahtaf.walkpad;

import java.util.UUID;

/** Classic protocol ported from Belt Control; see NOTICE and docs/PROTOCOL.md. */
public final class ClassicProtocol {
  public static final UUID SERVICE = uuid(0xfe00);
  public static final UUID NOTIFY = uuid(0xfe01);
  public static final UUID WRITE = uuid(0xfe02);
  public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
  public static final int MIN_SPEED = 5;
  public static final int MAX_SPEED = 60;

  private ClassicProtocol() {}

  private static UUID uuid(int id) {
    return UUID.fromString(
        String.format(java.util.Locale.ROOT, "0000%04x-0000-1000-8000-00805f9b34fb", id));
  }

  public static byte[] frame(int command, int parameter) {
    return new byte[] {
      (byte) 0xf7,
      (byte) 0xa2,
      (byte) command,
      (byte) parameter,
      (byte) ((0xa2 + command + parameter) & 255),
      (byte) 0xfd
    };
  }

  public record Status(
      int state, int speed, int mode, int seconds, int distanceHundredths, int steps) {}

  public static Status parse(byte[] bytes) {
    if (bytes == null || bytes.length < 17 || (bytes[0] & 255) != 0xf8 || (bytes[1] & 255) != 0xa2)
      return null;
    int speed = bytes[3] & 255, mode = bytes[4] & 255;
    if (speed > MAX_SPEED || mode > 2) return null;
    return new Status(bytes[2] & 255, speed, mode, be24(bytes, 5), be24(bytes, 8), be24(bytes, 11));
  }

  private static int be24(byte[] b, int i) {
    return ((b[i] & 255) << 16) | ((b[i + 1] & 255) << 8) | (b[i + 2] & 255);
  }
}
