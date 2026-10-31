// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Kahtaf

package dev.kahtaf.walkpad;

import static org.junit.Assert.*;

import java.util.*;
import java.util.function.Consumer;
import org.junit.Test;

public class PadControllerTest {
  static final class Clock implements CommandQueue.Scheduler {
    record Event(long at, long order, Runnable task) {}

    final PriorityQueue<Event> events =
        new PriorityQueue<>(Comparator.comparingLong(Event::at).thenComparingLong(Event::order));
    long time = 100000, order;

    public long now() {
      return time;
    }

    public void after(long delay, Runnable task) {
      events.add(new Event(time + delay, order++, task));
    }

    void advance(long duration) {
      long end = time + duration;
      while (!events.isEmpty() && events.peek().at() <= end) {
        Event e = events.remove();
        time = Math.max(time, e.at());
        e.task().run();
      }
      time = end;
    }
  }

  static final class Rig implements PadController.Observer {
    final Clock clock = new Clock();
    final List<byte[]> writes = new ArrayList<>();
    final PadController pad =
        new PadController(
            clock,
            (bytes, done) -> {
              writes.add(bytes);
              clock.after(5, () -> done.accept(null));
            },
            this);
    String error;

    public void changed() {}

    public void fatal(String reason) {
      error = reason;
    }

    void connect() {
      pad.begin();
      pad.receive(sample(0, 0, 2));
      clock.advance(250);
    }

    List<Integer> codes() {
      return writes.stream().map(b -> b[2] & 255).toList();
    }
  }

  static byte[] sample(int state, int speed, int mode) {
    return new byte[] {
      (byte) 0xf8,
      (byte) 0xa2,
      (byte) state,
      (byte) speed,
      (byte) mode,
      0,
      1,
      44,
      0,
      0,
      123,
      0,
      2,
      34,
      30,
      0,
      0,
      (byte) 0xfd
    };
  }

  @Test
  public void wireVectorsMatchDocumentedProtocol() {
    assertArrayEquals(
        new byte[] {(byte) 0xf7, (byte) 0xa2, 0, 0, (byte) 0xa2, (byte) 0xfd},
        ClassicProtocol.frame(0, 0));
    assertArrayEquals(
        new byte[] {(byte) 0xf7, (byte) 0xa2, 4, 1, (byte) 0xa7, (byte) 0xfd},
        ClassicProtocol.frame(4, 1));
    assertArrayEquals(
        new byte[] {(byte) 0xf7, (byte) 0xa2, 1, 0, (byte) 0xa3, (byte) 0xfd},
        ClassicProtocol.frame(1, 0));
    assertArrayEquals(
        new byte[] {(byte) 0xf7, (byte) 0xa2, 2, 2, (byte) 0xa6, (byte) 0xfd},
        ClassicProtocol.frame(2, 2));
  }

  @Test
  public void parsesPadTelemetryInMetricUnits() {
    var status = ClassicProtocol.parse(sample(2, 25, 1));
    assertNotNull(status);
    assertEquals(25, status.speed());
    assertEquals(300, status.seconds());
    assertEquals(123, status.distanceHundredths());
    assertEquals(546, status.steps());
  }

  @Test
  public void ignoresShortWrongAndImplausibleStatusFrames() {
    assertNull(ClassicProtocol.parse(null));
    assertNull(ClassicProtocol.parse(new byte[3]));
    byte[] wrong = sample(2, 10, 1);
    wrong[1] = (byte) 0xa7;
    assertNull(ClassicProtocol.parse(wrong));
    assertNull(ClassicProtocol.parse(sample(2, 255, 1)));
    assertNull(ClassicProtocol.parse(sample(2, 10, 3)));
  }

  @Test
  public void connectTimeoutUsesElapsedDurationRatherThanDeviceUptime() {
    Rig r = new Rig();
    r.pad.begin();
    assertNull(r.error);
    r.clock.advance(11000);
    assertNotNull(r.error);
  }

  @Test
  public void startWaitsForManualModeAndDoesNotInventMovement() {
    Rig r = new Rig();
    r.connect();
    r.pad.start();
    r.clock.advance(600);
    assertFalse(r.codes().contains(4));
    r.clock.advance(1000);
    assertTrue(r.codes().contains(4));
    assertEquals(PadController.Pending.START, r.pad.pending());
    assertFalse(r.pad.moving());
    r.pad.receive(sample(2, 10, 1));
    assertEquals(PadController.Pending.NONE, r.pad.pending());
    assertTrue(r.pad.moving());
  }

  @Test
  public void stopCancelsEveryUnsentStartAndSpeedFrame() {
    Rig r = new Rig();
    r.connect();
    r.pad.start();
    r.clock.advance(300);
    r.pad.stop();
    r.clock.advance(2000);
    assertFalse(r.codes().contains(4));
    assertTrue(r.writes.stream().anyMatch(b -> b[2] == 1 && b[3] == 0));
    assertFalse(r.writes.stream().anyMatch(b -> b[2] == 1 && b[3] > 0));
    assertEquals(PadController.Pending.STOP, r.pad.pending());
    r.pad.receive(sample(0, 0, 2));
    assertEquals(PadController.Pending.NONE, r.pad.pending());
    assertTrue(r.pad.idle());
  }

  @Test
  public void cannotStartTwiceOrStartWhileAlreadyMoving() {
    Rig r = new Rig();
    r.connect();
    r.pad.receive(sample(9, 10, 1));
    assertFalse(r.pad.idle());
    r.pad.start();
    assertEquals(PadController.Pending.NONE, r.pad.pending());
    r.pad.receive(sample(0, 0, 2));
    r.pad.start();
    int before = r.writes.size();
    r.pad.start();
    assertEquals(before, r.writes.size());
  }

  @Test
  public void staleStatusBlocksStartAndSpeedButStillAllowsStop() {
    Rig r = new Rig();
    r.connect();
    r.clock.advance(4000);
    int target = r.pad.target();
    assertFalse(r.pad.fresh());
    r.pad.start();
    r.pad.adjust(1);
    assertEquals(target, r.pad.target());
    assertEquals(PadController.Pending.NONE, r.pad.pending());
    r.pad.stop();
    r.clock.advance(1000);
    assertTrue(r.writes.stream().anyMatch(b -> b[2] == 1 && b[3] == 0));
  }

  @Test
  public void requestedSpeedStaysInsideDocumentedRange() {
    Rig r = new Rig();
    r.connect();
    for (int i = 0; i < 100; i++) r.pad.adjust(1);
    assertEquals(60, r.pad.target());
    for (int i = 0; i < 100; i++) r.pad.adjust(-1);
    assertEquals(5, r.pad.target());
    assertTrue(r.pad.idle());
  }

  @Test
  public void onlyConfirmedStoppedSampleClearsStopping() {
    Rig r = new Rig();
    r.connect();
    r.pad.receive(sample(2, 20, 1));
    r.pad.stop();
    r.clock.advance(800);
    r.pad.receive(sample(2, 20, 1));
    assertEquals(PadController.Pending.STOP, r.pad.pending());
    r.pad.receive(sample(4, 0, 1));
    assertEquals(PadController.Pending.STOP, r.pad.pending());
    r.pad.receive(sample(0, 0, 2));
    assertEquals(PadController.Pending.NONE, r.pad.pending());
  }

  @Test
  public void unconfirmedCommandFailsWithoutLateRetry() {
    Rig r = new Rig();
    r.connect();
    r.pad.start();
    r.clock.advance(15000);
    assertEquals(PadController.Pending.FAILED, r.pad.pending());
    long starts = r.writes.stream().filter(b -> b[2] == 4).count();
    assertEquals(1, starts);
    r.pad.receive(sample(0, 0, 2));
    assertFalse(r.pad.idle());
  }

  @Test
  public void closeDropsQueuedMotionAndIgnoresNotifications() {
    Rig r = new Rig();
    r.connect();
    r.pad.start();
    r.pad.close();
    r.clock.advance(10000);
    assertFalse(r.codes().contains(4));
    r.pad.receive(sample(2, 10, 1));
    assertFalse(r.pad.fresh());
  }

  @Test
  public void queueTimesOutOnceAndIgnoresLateAck() {
    Clock clock = new Clock();
    List<String> errors = new ArrayList<>();
    List<Consumer<String>> acks = new ArrayList<>();
    CommandQueue q = new CommandQueue(clock, (frame, done) -> acks.add(done), errors::add);
    q.replace(List.of(new CommandQueue.Command(4, 1), new CommandQueue.Command(1, 10)));
    clock.advance(4000);
    assertEquals(1, errors.size());
    acks.get(0).accept(null);
    clock.advance(10000);
    assertEquals(1, acks.size());
  }

  @Test
  public void queueNeverOverlapsGattWritesAndIgnoresDuplicateAcks() {
    Clock clock = new Clock();
    List<Consumer<String>> acks = new ArrayList<>();
    List<Long> times = new ArrayList<>();
    CommandQueue q =
        new CommandQueue(
            clock,
            (frame, done) -> {
              acks.add(done);
              times.add(clock.now());
            },
            reason -> fail(reason));
    q.replace(List.of(new CommandQueue.Command(2, 1, 600, null), new CommandQueue.Command(4, 1)));
    clock.advance(100);
    assertEquals(1, acks.size());
    acks.get(0).accept(null);
    acks.get(0).accept(null);
    clock.advance(599);
    assertEquals(1, acks.size());
    clock.advance(1);
    assertEquals(2, acks.size());
    assertEquals(700, times.get(1) - times.get(0));
  }

  @Test
  public void deviceSleepCannotCauseAnExpiredStartToRunLater() {
    Clock clock = new Clock();
    List<Integer> writes = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    CommandQueue q =
        new CommandQueue(
            clock,
            (frame, done) -> {
              writes.add(frame[2] & 255);
              done.accept(null);
            },
            errors::add);
    q.replace(List.of(new CommandQueue.Command(2, 1, 600, null), new CommandQueue.Command(4, 1)));
    clock.time += 6000;
    clock.advance(0);
    assertEquals(List.of(2), writes);
    assertEquals(1, errors.size());
  }

  @Test
  public void stopReportedByPadCancelsQueuedSpeedChange() {
    Rig r = new Rig();
    r.connect();
    // The initial status poll keeps the radio in its spacing window.
    r.clock.advance(800);
    r.pad.receive(sample(2, 10, 1));
    r.pad.adjust(1);
    r.pad.receive(sample(99, 0, 2));
    assertEquals(PadController.Pending.STOP, r.pad.pending());
    assertFalse(r.pad.canAdjust());
    r.clock.advance(2000);
    assertFalse(r.writes.stream().anyMatch(b -> b[2] == 1 && b[3] == 11));
    assertTrue(r.writes.stream().anyMatch(b -> b[2] == 1 && b[3] == 0));
  }

  @Test
  public void stoppedPadInStandbyAcceptsStartDespiteAlternateRawState() {
    Rig r = new Rig();
    r.connect();
    r.pad.receive(sample(2, 0, 2));
    assertTrue("Fresh zero speed in standby must allow Start", r.pad.idle());
    r.pad.adjust(1);
    assertEquals(11, r.pad.target());
    r.pad.start();
    r.clock.advance(2000);
    assertTrue("Explicit Start must send the start command", r.codes().contains(4));
  }

  @Test
  public void walkingInManualModeAllowsSpeedChangeDespiteAlternateRawState() {
    Rig r = new Rig();
    r.connect();
    r.pad.receive(sample(1, 10, 1));
    assertTrue("Confirmed movement in manual mode must allow speed controls", r.pad.canAdjust());
    r.pad.adjust(1);
    r.clock.advance(600);
    assertTrue(r.writes.stream().anyMatch(b -> b[2] == 1 && b[3] == 11));
    r.pad.receive(sample(1, 11, 1));
    assertEquals(PadController.Pending.NONE, r.pad.pending());
  }

  @Test
  public void connectRequestsManualWhenStoppedWithoutStartingTheBelt() {
    Rig r = new Rig();
    r.pad.prepareManualOnConnection();
    r.pad.begin();
    r.pad.receive(sample(2, 0, 2));
    assertEquals(PadController.Pending.MODE, r.pad.pending());
    assertFalse(r.pad.idle());
    r.clock.advance(800);
    assertTrue(r.writes.stream().anyMatch(b -> b[2] == 2 && b[3] == 1));
    assertFalse(r.codes().contains(4));
    assertFalse(r.writes.stream().anyMatch(b -> b[2] == 1 && b[3] > 0));
    r.pad.receive(sample(0, 0, 1));
    assertEquals(PadController.Pending.NONE, r.pad.pending());
    assertTrue(r.pad.idle());
    r.pad.stop();
    r.clock.advance(1000);
    r.pad.receive(sample(2, 0, 2));
    assertTrue(r.pad.standby());
    assertEquals(PadController.Pending.NONE, r.pad.pending());
  }

  @Test
  public void connectDoesNotChangeModeOnAnAlreadyMovingPad() {
    Rig r = new Rig();
    r.pad.prepareManualOnConnection();
    r.pad.begin();
    r.pad.receive(sample(1, 10, 0));
    r.clock.advance(1000);
    assertFalse(r.writes.stream().anyMatch(b -> b[2] == 2));
    assertFalse(r.codes().contains(4));
    assertFalse(r.pad.canAdjust());
  }

  @Test
  public void stopCancelsQueuedManualPreparationAndStandbyRequiresConfirmation() {
    Rig r = new Rig();
    r.pad.prepareManualOnConnection();
    r.pad.begin();
    r.pad.receive(sample(2, 0, 2));
    r.pad.stop();
    r.clock.advance(1200);
    assertFalse(r.writes.stream().anyMatch(b -> b[2] == 2 && b[3] == 1));
    assertFalse(r.pad.standby());
    r.pad.receive(sample(0, 0, 1));
    assertFalse(r.pad.standby());
    r.pad.receive(sample(2, 0, 2));
    assertTrue(r.pad.standby());
  }

  @Test
  public void explicitModeChangeWaitsForMatchingEchoWithoutStarting() {
    Rig r = new Rig();
    r.connect();
    r.pad.receive(sample(0, 0, 1));
    r.pad.setMode(0);
    assertEquals(PadController.Pending.MODE, r.pad.pending());
    assertFalse(r.pad.canChangeMode());
    r.clock.advance(1000);
    r.pad.receive(sample(0, 0, 1));
    assertEquals(PadController.Pending.MODE, r.pad.pending());
    r.pad.receive(sample(0, 0, 0));
    assertEquals(PadController.Pending.NONE, r.pad.pending());
    assertTrue(r.writes.stream().anyMatch(b -> b[2] == 2 && b[3] == 0));
    assertFalse(r.codes().contains(4));
    assertFalse(r.writes.stream().anyMatch(b -> b[2] == 1 && b[3] > 0));
  }

  @Test
  public void modeChangesBlockedWhileMovingStaleOrBusy() {
    Rig r = new Rig();
    r.connect();
    r.pad.receive(sample(1, 10, 1));
    r.pad.setMode(0);
    assertFalse(r.pad.canChangeMode());
    assertEquals(PadController.Pending.NONE, r.pad.pending());
    r.pad.receive(sample(0, 0, 1));
    r.clock.advance(4000);
    r.pad.setMode(0);
    assertFalse(r.pad.canChangeMode());
    assertEquals(PadController.Pending.NONE, r.pad.pending());
    r.pad.receive(sample(0, 0, 1));
    r.pad.start();
    r.pad.setMode(0);
    assertEquals(PadController.Pending.START, r.pad.pending());
    r.clock.advance(2000);
    assertFalse(r.writes.stream().anyMatch(b -> b[2] == 2 && b[3] == 0));
  }

  @Test
  public void deviceSleepCannotApplyExpiredAutoMode() {
    Clock clock = new Clock();
    List<Integer> modes = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    CommandQueue queue =
        new CommandQueue(
            clock,
            (frame, done) -> {
              if (frame[2] == 2) modes.add((int) frame[3]);
              done.accept(null);
            },
            errors::add);
    queue.poll();
    queue.replace(List.of(new CommandQueue.Command(2, 0)));
    clock.time += 6000;
    clock.advance(0);
    assertTrue("An expired mode change must be discarded", modes.isEmpty());
    assertEquals(1, errors.size());
  }
}
