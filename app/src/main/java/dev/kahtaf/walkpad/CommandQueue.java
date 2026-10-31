// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Kahtaf

package dev.kahtaf.walkpad;

import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Consumer;

/** Serial GATT writes. Replacing a plan discards every unsent command in that plan. */
public final class CommandQueue {
  public interface Scheduler {
    long now();

    void after(long millis, Runnable task);
  }

  public interface Wire {
    void write(byte[] frame, Consumer<String> completion);
  }

  public record Command(int code, int value, long settleMillis, Runnable completed) {
    public Command(int code, int value) {
      this(code, value, 200, null);
    }
  }

  private final Scheduler scheduler;
  private final Wire wire;
  private final Consumer<String> fatal;
  private final ArrayDeque<Command> queue = new ArrayDeque<>();
  private long nextWrite;
  private long epoch;
  private long writeId;
  private long motionDeadline;
  private boolean busy, closed;

  public CommandQueue(Scheduler scheduler, Wire wire, Consumer<String> fatal) {
    this.scheduler = scheduler;
    this.wire = wire;
    this.fatal = fatal;
  }

  public void replace(List<Command> commands) {
    if (closed) return;
    epoch++;
    motionDeadline = scheduler.now() + 5000;
    queue.clear();
    queue.addAll(commands);
    pump();
  }

  public boolean isIdle() {
    return !busy && queue.isEmpty();
  }

  public void poll() {
    if (!closed && isIdle()) {
      queue.add(new Command(0, 0));
      pump();
    }
  }

  public void close() {
    closed = true;
    epoch++;
    writeId++;
    queue.clear();
  }

  private void pump() {
    if (closed || busy || queue.isEmpty()) return;
    long remaining = nextWrite - scheduler.now();
    if (remaining > 0) {
      scheduler.after(remaining, this::pump);
      return;
    }
    Command command = queue.remove();
    if ((command.code() == 4
            || command.code() == 1 && command.value() > 0
            || command.code() == 2 && command.value() != 2)
        && scheduler.now() > motionDeadline) {
      close();
      fatal.accept("Command expired · reconnect");
      return;
    }
    long commandEpoch = epoch, id = ++writeId;
    busy = true;
    scheduler.after(3000, () -> finish(id, commandEpoch, command, "Bluetooth write timed out"));
    wire.write(
        ClassicProtocol.frame(command.code(), command.value()),
        error -> finish(id, commandEpoch, command, error));
  }

  private void finish(long id, long commandEpoch, Command command, String error) {
    if (closed || !busy || id != writeId) return;
    busy = false;
    if (error != null) {
      close();
      fatal.accept(error);
      return;
    }
    nextWrite = scheduler.now() + command.settleMillis();
    if (commandEpoch == epoch && command.completed() != null) command.completed().run();
    pump();
  }
}
