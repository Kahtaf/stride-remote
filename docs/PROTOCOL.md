# Classic WalkingPad protocol

This app ports the classic driver from [Belt Control](https://github.com/morsela/beltcontrol/blob/6809303910f87a4f1fe68eb7c5caad00015edff9/packages/belt-drivers/src/drivers.ts), revision `6809303910f87a4f1fe68eb7c5caad00015edff9` (Apache-2.0). The Java implementation changes transport, lifecycle, command serialization, confirmation and watch UI; it is not the upstream TypeScript app.

The original driver snapshot used for the port has SHA256 `12487fbc75d86c455763e20ac0c0b1e02fe574f9a4e6f39051077916e2a3216e`. Research snapshots and local device captures are excluded from this repository; the pinned upstream source remains the reference.

## GATT and frames

| Purpose | UUID |
| --- | --- |
| Service | `0000fe00-0000-1000-8000-00805f9b34fb` |
| Notifications | `0000fe01-0000-1000-8000-00805f9b34fb` |
| Writes | `0000fe02-0000-1000-8000-00805f9b34fb` |

Commands are six bytes: `F7 A2 command parameter checksum FD`, where `checksum = (A2 + command + parameter) & FF`.

| Command | Meaning |
| --- | --- |
| `00 00` | Request current status |
| `01 speed` | Speed in tenths of km/h; zero requests stop |
| `02 mode` | `0` Auto, `1` Manual, `2` standby |
| `04 01` | Start |

Status begins `F8 A2`. The app decodes raw state at byte 2, speed at byte 3, mode at byte 4, then 24-bit big-endian elapsed seconds, distance in hundredths of km, and steps at offsets 5, 8 and 11. Unverified raw-state codes are retained diagnostically and do not gate Start or speed changes. Session counters are the pad's values and may reset; the app does not store workout history.

## Sequencing and confirmation

The queue keeps only one GATT write in flight, normally waits 200 ms between acknowledged writes, and waits 600 ms after selecting a mode. Replacing a plan discards its unsent commands; Stop replaces pending movement. Mode/movement commands expire after five seconds rather than running after a long scheduler delay. A Bluetooth write acknowledgement does not prove a motor command succeeded: matching status confirms the requested action, with a separate timeout.

The controller polls once per second and treats status older than 3.5 seconds as stale. Start, speed and mode changes require fresh telemetry. Start uses explicit two-tap confirmation, selects Manual and sends the documented start/speed sequence. Connecting to a stopped pad prepares Manual without Start; it does not change mode on an already moving pad. Explicit Disconnect requests zero speed and standby before closing. Link loss cannot send a stop command.

These protocol details describe supported behavior, not a guarantee for every product with the WalkingPad name. The proprietary 2.4 GHz physical remote is a separate radio interface.
