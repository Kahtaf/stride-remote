# Security

Only the latest release receives fixes. This is an experimental Bluetooth controller for motorized hardware, not a safety device.

Report vulnerabilities privately through [GitHub private vulnerability reporting](https://github.com/Kahtaf/stride-remote/security/advisories/new). Include the affected version, reproduction steps, impact, and a proposed fix if available. Do not post private device identifiers or active pairing codes in public issues.

The app has no Internet permission, telemetry, accounts or phone relay. A remembered Bluetooth device name/address is stored locally in app-private preferences. Up to 100 recent protocol events are kept in memory for local ADB diagnostics. Android backup is disabled.

The classic pad protocol does not provide an app-level authentication scheme. Android controls Bluetooth access; the app validates the expected service and characteristics before issuing commands. Keep other controllers disconnected while using Stride Remote.

Stop remains available when telemetry is stale, but a lost radio link, killed process or powered-off watch cannot deliver a stop command. Keep the physical stop/power control available. Never rely on this app for an emergency stop.
