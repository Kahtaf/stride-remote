// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Kahtaf

package dev.kahtaf.walkpad;

import java.util.List;

public final class PadController {
  public enum Pending {
    NONE,
    MODE,
    START,
    SPEED,
    STOP,
    FAILED
  }

  public interface Observer {
    void changed();

    void fatal(String reason);
  }

  private final CommandQueue.Scheduler clock;
  private final Observer observer;
  private final CommandQueue commands;
  private boolean open = true;
  private long lastStatus = -1, awaitingSince = -1, operationId;
  private ClassicProtocol.Status status;
  private Pending pending = Pending.NONE;
  private int target = 10;
  private String problem = "Reading pad…";
  private boolean prepareManualOnStatus;
  private int requestedMode;

  public PadController(CommandQueue.Scheduler clock, CommandQueue.Wire wire, Observer observer) {
    this.clock = clock;
    this.observer = observer;
    this.connectedAt = clock.now();
    commands =
        new CommandQueue(
            clock,
            wire,
            reason -> {
              close();
              observer.fatal(reason);
            });
  }

  public void begin() {
    tick();
  }

  public void prepareManualOnConnection() {
    prepareManualOnStatus = true;
  }

  private void tick() {
    if (!open) return;
    commands.poll();
    if (lastStatus < 0 && clock.now() > connectedAt + 10000) {
      close();
      observer.fatal("No data · reconnect");
      return;
    }
    observer.changed();
    clock.after(1000, this::tick);
  }

  private final long connectedAt;

  public void receive(byte[] value) {
    ClassicProtocol.Status sample = ClassicProtocol.parse(value);
    if (!open || sample == null) return;
    boolean prepareManual = prepareManualOnStatus;
    prepareManualOnStatus = false;
    boolean externalStop =
        (pending == Pending.SPEED && (sample.speed() == 0 || sample.mode() != 1))
            || (pending == Pending.START
                && status != null
                && status.speed() > 0
                && sample.speed() == 0);
    status = sample;
    lastStatus = clock.now();
    if (externalStop) {
      stop();
      return;
    }
    if (prepareManual && sample.speed() == 0 && sample.mode() != 1 && pending == Pending.NONE) {
      setMode(1);
      return;
    }
    if (pending == Pending.NONE) problem = "";
    if (pending == Pending.NONE && sample.speed() > 0)
      target = Math.max(ClassicProtocol.MIN_SPEED, sample.speed());
    if (awaitingSince >= 0) {
      boolean confirmed =
          switch (pending) {
            case MODE -> sample.mode() == requestedMode;
            case START -> sample.speed() == target && sample.mode() == 1;
            case STOP -> sample.speed() == 0 && sample.mode() == 2;
            case SPEED -> sample.speed() == target;
            default -> false;
          };
      if (confirmed) {
        pending = Pending.NONE;
        awaitingSince = -1;
        problem = "";
      }
    }
    observer.changed();
  }

  public boolean fresh() {
    return open && lastStatus >= 0 && clock.now() - lastStatus <= 3500;
  }

  public boolean moving() {
    return status != null && status.speed() > 0;
  }

  public boolean idle() {
    return fresh() && status.speed() == 0 && pending == Pending.NONE;
  }

  public boolean standby() {
    return idle() && status.mode() == 2;
  }

  public boolean canChangeMode() {
    return idle();
  }

  public boolean uncertain() {
    return pending == Pending.FAILED || !fresh();
  }

  public boolean canAdjust() {
    return fresh()
        && (pending == Pending.NONE || pending == Pending.SPEED)
        && (moving() && status.mode() == 1 || idle());
  }

  public ClassicProtocol.Status status() {
    return status;
  }

  public Pending pending() {
    return pending;
  }

  public int target() {
    return target;
  }

  public String problem() {
    return problem;
  }

  public void setMode(int mode) {
    if (mode != 0 && mode != 1)
      throw new IllegalArgumentException("Choose Manual or Auto; use Stop for standby");
    if (!canChangeMode() || status.mode() == mode) return;
    prepareManualOnStatus = false;
    requestedMode = mode;
    arm(Pending.MODE);
    commands.replace(List.of(new CommandQueue.Command(2, mode, 600, this::sent)));
    observer.changed();
  }

  public void adjust(int delta) {
    if (!canAdjust()) return;
    target =
        Math.max(
            ClassicProtocol.MIN_SPEED,
            Math.min(ClassicProtocol.MAX_SPEED, target + Integer.signum(delta)));
    if (moving()) {
      arm(Pending.SPEED);
      commands.replace(List.of(new CommandQueue.Command(1, target, 200, this::sent)));
    }
    observer.changed();
  }

  public void start() {
    if (!idle()) return;
    arm(Pending.START);
    commands.replace(
        List.of(
            new CommandQueue.Command(0, 0),
            new CommandQueue.Command(2, 1, 600, null),
            new CommandQueue.Command(4, 1),
            new CommandQueue.Command(1, target, 200, this::sent)));
    observer.changed();
  }

  public void stop() {
    if (!open) return;
    prepareManualOnStatus = false;
    arm(Pending.STOP);
    commands.replace(
        List.of(new CommandQueue.Command(1, 0), new CommandQueue.Command(2, 2, 200, this::sent)));
    observer.changed();
  }

  private void arm(Pending action) {
    operationId++;
    pending = action;
    awaitingSince = -1;
    problem = "";
  }

  private void sent() {
    awaitingSince = clock.now();
    long id = operationId;
    clock.after(
        12000,
        () -> {
          if (!open || id != operationId || pending == Pending.NONE) return;
          pending = Pending.FAILED;
          awaitingSince = -1;
          problem = "Not confirmed · check belt";
          observer.changed();
        });
  }

  public void close() {
    open = false;
    prepareManualOnStatus = false;
    operationId++;
    commands.close();
  }
}
