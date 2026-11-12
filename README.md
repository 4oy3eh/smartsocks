# SmartSocks

Pressure-sensing socks: 6 FSR sensors per foot, streamed over Bluetooth (SPP) to an app that shows live sensor load,
center of pressure (COP) and left/right balance, and records sessions.

Kotlin Multiplatform + Compose Multiplatform — **Android** and **Desktop (Windows)** from one codebase.
A built-in simulator lets you run everything without hardware.

> Status: rewrite in progress. Core (protocol, calibration, COP, simulator) first, then UI.

- Device protocol & math notes: [docs/legacy-analysis.md](docs/legacy-analysis.md) (in Russian)
- Previous version (archived): [SensorApplication](https://github.com/4oy3eh/SensorApplication)
