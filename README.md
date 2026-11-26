# TickMode

An Android interval timer for repeatable vibration and sound signals.

TickMode runs one session at a time. Set an interval, choose a signal, and start the session. It continues until you pause or stop it.

## Why I made it

TickMode started as a personal experiment inspired by Whiterose from *Mr. Robot* and the character's unusual relationship with time, precision and routine.

I became interested in a simple question: what would happen if I introduced deliberate, recurring signals into my everyday life?

Would I become more aware of how I spend my time? Would my routines change? Would having a small, consistent interruption help me become more intentional about what I'm doing?

I don't know yet. That's the experiment.

TickMode is my attempt to explore that idea through a simple tool, without turning it into another productivity system.

## Features

- Repeating interval sessions with no configured end time
- Short, Medium and Long vibration options
- Sound signals, including Soft Bell and custom audio
- Pause, resume and stop controls
- Persistent session notification
- Local session history
- Dark, minimal interface

## Download

The latest APK is available under [GitHub Releases](https://github.com/ShehryarMalik/TickMode/releases/latest).

For installation instructions, see [Installation](docs/INSTALLATION.md).

## Background operation

Android manufacturers apply additional battery restrictions that can affect background services.

On some devices, changing Android's standard battery optimization setting is not enough. Additional configuration may be required.

See [Background Setup](docs/BACKGROUND_SETUP.md) before relying on a session with the screen off.

## Documentation

- [Installation](docs/INSTALLATION.md)
- [Background Setup](docs/BACKGROUND_SETUP.md)
- [Build from Source](docs/BUILD_FROM_SOURCE.md)
- [Release and Signing](docs/RELEASING.md)
- [Changelog](CHANGELOG.md)

## Building

TickMode is an Android application written in Kotlin using Jetpack Compose.

The project includes the Gradle wrapper. See [Build from Source](docs/BUILD_FROM_SOURCE.md) for requirements and instructions.

## Project status

TickMode is an independently developed project. It is tested on a limited set of Android devices, and background execution can vary between manufacturers and firmware versions.

Bug reports and practical feedback are welcome.

---

*TickMode is an independent project inspired by a fictional character from Mr. Robot. It is not affiliated with or endorsed by the show's creators or rights holders.*
