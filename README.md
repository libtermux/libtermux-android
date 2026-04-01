<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android%2024%2B-green?style=flat-square&logo=android" />
  <img src="https://img.shields.io/badge/Language-Kotlin-blue?style=flat-square&logo=kotlin" />
  <img src="https://img.shields.io/badge/Architecture-arm64%20%7C%20x86__64%20%7C%20arm%20%7C%20x86-orange?style=flat-square" />
  <img src="https://img.shields.io/badge/License-Apache%202.0-lightgrey?style=flat-square" />
  <img src="https://img.shields.io/badge/JitPack-1.0.0-red?style=flat-square" />
</p>

<h1 align="center">📦 libtermux-android</h1>
<p align="center"><b>World's first Standalone Termux SDK for Android.</b><br/>
Embed a full Linux environment inside any Android app — no Termux installation required.</p>

---

## 🚀 What is this?

**libtermux-android** is a high-performance Android library that bootstraps a complete, isolated Linux
environment (Termux runtime) inside your app's private storage. Your users get all the power of a
Linux terminal without ever knowing it exists.

```
Your App → LibTermux SDK → Isolated Linux Env → bash, python, node, ruby, pkg install...
```

## ✨ Features

| Feature | Description |
|---|---|
| **Standalone Mode** | No Termux app required — bootstrap is auto-installed |
| **Kotlin DSL API** | Clean, idiomatic Kotlin interface |
| **Java Compatible** | Full Java Builder pattern support |
| **Multi-language** | Python · Node.js · Bash · Ruby · Perl + any pkg |
| **Package Manager** | `pkg install`, `pip install`, `npm install -g` |
| **Streaming Output** | Real-time `Flow<OutputLine>` from running processes |
| **Background Service** | Keep scripts running when app is minimized |
| **TerminalView** | Drop-in terminal UI widget |
| **JNI PTY Layer** | Full pseudo-terminal support for interactive programs |
| **Multi-arch** | arm64-v8a · x86_64 · armeabi-v7a · x86 |

---

## 📦 Installation

### Gradle (JitPack)

```kotlin
// settings.gradle.kts
repositories {
    maven { url = uri("https://jitpack.io") }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.libtermux:libtermux-android:1.0.0")

    // Optional: terminal UI widget
    implementation("com.github.libtermux:terminal-view:1.0.0")
}
```

### Maven

```xml
<repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
</repository>

<dependency>
    <groupId>com.github.libtermux</groupId>
    <artifactId>libtermux-android</artifactId>
    <version>1.0.0</version>
</dependency>
```

---

## ⚡ Quick Start

### Kotlin

```kotlin
class MyActivity : AppCompatActivity() {

    private val termux by lazy {
        LibTermux.create(this) {
            autoInstall  = true
            logLevel     = LogLevel.DEBUG
            env("MY_APP", "libtermux")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            // 1. Initialize (downloads bootstrap on first run ~30MB)
            termux.initialize().collect { state ->
                when (state) {
                    is InstallState.Downloading -> showProgress(state.progress)
                    is InstallState.Completed   -> onReady()
                    is InstallState.Failed      -> showError(state.error)
                    else -> {}
                }
            }
        }
    }

    private suspend fun onReady() {
        val bridge = termux.bridge

        // Run any shell command
        val result = bridge.run("uname -a")
        println(result.stdout)

        // Run Python
        val py = bridge.python("""
            import json, sys
            print(json.dumps({"python": sys.version, "status": "works!"}))
        """)
        println(py.stdout)

        // Install packages
        bridge.install("git", "curl", "wget")

        // Stream real-time output
        bridge.runStreaming("ping -c 5 8.8.8.8").collect { line ->
            when (line) {
                is OutputLine.Stdout -> updateUI(line.text)
                is OutputLine.Stderr -> showError(line.text)
                is OutputLine.Exit   -> println("done: ${line.code}")
            }
        }
    }
}
```

### Java

```java
LibTermux termux = LibTermux.builder(this)
    .autoInstall(true)
    .logLevel(LogLevel.DEBUG)
    .build();

// Blocking init (run on background thread!)
new Thread(() -> {
    try {
        termux.initializeBlocking();
        String output = termux.getBridge().runOrThrow("echo Hello Linux!");
        Log.d("TAG", output); // Hello Linux!
    } catch (Exception e) {
        Log.e("TAG", "Failed", e);
    }
}).start();
```

---

## 🔧 Advanced Usage

### Background Execution

```kotlin
// Start background service
TermuxBackgroundService.start(context, config)

// Run a long-running server in background
TermuxBackgroundService.runCommand(context, "node-server", "node server.js")
```

### Terminal UI Widget

```xml
<!-- activity_main.xml -->
<com.libtermux.view.TerminalView
    android:id="@+id/terminal"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

```kotlin
val session = termux.sessions.createSession("main")
binding.terminal.attachSession(session)
session.run("bash") // Start interactive shell
```

### Multiple Sessions

```kotlin
val session1 = termux.sessions.createSession("python-repl")
val session2 = termux.sessions.createSession("node-server")

session1.run("python3 myapp.py")
session2.run("node server.js")

termux.sessions.closeSession(session1.session.id)
```

### Package Management

```kotlin
// Install packages
termux.packages.install("python", "nodejs", "git", "curl")
termux.packages.pipInstall("requests", "numpy", "flask")
termux.packages.npmInstall("express", "axios")

// Search
val results = termux.packages.search("machine learning")

// List installed
val installed = termux.packages.listInstalled()
```

### File Operations

```kotlin
// Write a Python script to HOME
termux.bridge.writeFile("app.py", """
    import flask
    app = flask.Flask(__name__)

    @app.route('/')
    def hello(): return "Hello from LibTermux!"

    app.run(host='127.0.0.1', port=8080)
""")

// Run it
termux.bridge.run("python3 ~/app.py")

// Read output
val content = termux.bridge.readFile("output.txt")
```

---

## 🔐 Shizuku Integration (Elevated Privileges)

libtermux-android optionally integrates with Shizuku to run commands with elevated (root/system) privileges.
This allows your app to perform system-level operations like accessing protected files, modifying system settings, or executing privileged commands—all without requiring the user to manually install Shizuku or root their device (Shizuku can run via ADB or root).

The library provides a simple API that falls back to normal execution when Shizuku is unavailable, so your app remains functional in all environments.

## Installation

Add the Shizuku module dependency:

```kotlin
dependencies {
    implementation("com.github.libtermux:libtermux-android:1.0.0")
    implementation("com.github.libtermux:shizuku:1.0.0") // optional: elevated commands
}
```

## Usage

```kotlin
class MyActivity : AppCompatActivity() {
    private lateinit var shizukuTermux: ShizukuTermux

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        shizukuTermux = ShizukuTermux.getInstance(this) {
            autoInstall = true
        }

        lifecycleScope.launch {
            shizukuTermux.initialize().collect { state ->
                if (state is InstallState.Completed) {
                    // Request Shizuku permission if needed
                    if (!shizukuTermux.isShizukuPermissionGranted) {
                        shizukuTermux.requestShizukuPermission()
                    }

                    // Run normal command
                    val normal = shizukuTermux.bridge.run("echo Hello")
                    println(normal.stdout)

                    // Run elevated command (e.g., read system files)
                    val elevated = shizukuTermux.runElevated("cat /system/build.prop")
                    println("Exit code: ${elevated.exitCode}")
                }
            }
        }
    }
}
```

## Permission

Add the following permission to your AndroidManifest.xml:

```xml
<uses-permission android:name="dev.rikka.shizuku.permission.API_V23" />
```

Optionally, include the Shizuku provider for automatic service binding:

```xml
<provider
    android:name="rikka.shizuku.ShizukuProvider"
    android:authorities="${applicationId}.shizuku"
    android:enabled="true"
    android:exported="false" />
```

## API Reference

**Method Description**

ShizukuTermux.getInstance(context, config) Get singleton instance (also supports DSL).
isShizukuAvailable Check if Shizuku service is installed and running.
isShizukuPermissionGranted Check if permission has been granted.
suspend fun requestShizukuPermission() Request permission (suspends until granted/denied).
suspend fun runElevated(command, env, workDir) Execute command with elevated privileges. Returns ElevatedResult.

## Fallback Behavior

If Shizuku is not installed, not running, or permission is denied, runElevated() automatically falls back to normal execution within the Termux environment. You can check the elevated flag in the result to know how it was executed.

---

## 🏗️ Project Structure

```
libtermux-android/
├── core/                          # Main SDK library
│   └── src/main/
│       ├── kotlin/com/libtermux/
│       │   ├── LibTermux.kt       # ← Main entry point
│       │   ├── TermuxConfig.kt    # ← Configuration DSL
│       │   ├── bootstrap/         # Bootstrap installer
│       │   ├── executor/          # Command execution engine
│       │   ├── fs/                # Virtual file system
│       │   ├── pkg/               # Package manager
│       │   ├── bridge/            # Developer API bridge
│       │   ├── service/           # Background service
│       │   └── utils/             # Utilities + JNI bindings
│       └── jni/                   # C++ native layer (PTY, process, symlinks)
│
├── terminal-view/                 # Terminal UI widget (optional)
│   └── src/main/kotlin/
│       └── com/libtermux/view/
│           └── TerminalView.kt
│
├── shizuku/                       # Shizuku integration for elevated commands (optional)
│   └── src/main/kotlin/
│       └── com/libtermux/shizuku/
│           ├── ShizukuTermux.kt   # Entry point for elevated execution
│           └── ElevatedResult.kt  # Result class for elevated commands
│
├── sample/                        # Demo app
│   └── src/main/kotlin/
│       └── com/libtermux/sample/
│           ├── MainActivity.kt
│           └── MainViewModel.kt
│
├── scripts/
│   ├── setup_dev.sh               # Developer environment setup
│   └── publish_local.sh           # Publish to local Maven
│
└── .github/workflows/
    ├── ci.yml                     # CI: lint + test + build
    └── release.yml                # Release: publish to Maven Central
```

---

## 🔐 Permissions

Add to your `AndroidManifest.xml`:

```xml
<!-- Required for bootstrap download -->
<uses-permission android:name="android.permission.INTERNET" />

<!-- Required for background service -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

<!-- Optional: for notifications -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

---

## 📊 Architecture

```
┌──────────────────────────────────────────────────────────┐
│                      Your Android App                     │
│                                                           │
│  LibTermux.create(context) { ... }                        │
│       │                                                   │
│  ┌────▼────────────────────────────────────────────────┐  │
│  │              LibTermux (Singleton)                  │  │
│  │  ┌─────────────┐  ┌──────────────┐  ┌───────────┐  │  │
│  │  │TermuxBridge │  │SessionManager│  │PackageMgr │  │  │
│  │  └──────┬──────┘  └──────┬───────┘  └─────┬─────┘  │  │
│  │         └───────────────┬┘                │         │  │
│  │                  ┌──────▼──────┐           │         │  │
│  │                  │CommandExec  │◄──────────┘         │  │
│  │                  └──────┬──────┘                     │  │
│  │  ┌────────────────────┐ │ ┌──────────────────────┐   │  │
│  │  │VirtualFileSystem   │ │ │BootstrapInstaller    │   │  │
│  │  │  /usr /home /tmp   │◄┘ │  (Download + Extract)│   │  │
│  │  └────────────────────┘   └──────────────────────┘   │  │
│  │                                                       │  │
│  │  ┌────────────────────────────────────────────────┐   │  │
│  │  │     libtermux_jni.so  (C++ Native Layer)        │  │  │
│  │  │  PTY · Process Fork · Symlinks · chmod          │  │  │
│  │  └────────────────────────────────────────────────┘   │  │
│  └────────────────────────────────────────────────────┘  │
│                                                           │
│  ┌──────────────────────────────────────────────────────┐ │
│  │   App Private Storage: filesDir/libtermux/            │ │
│  │   └── usr/bin/bash, python3, node ...                 │ │
│  └──────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────┘
```

---

## 🤝 Contributing

1. Fork the repo
2. Run `./scripts/setup_dev.sh`
3. Create a feature branch: `git checkout -b feature/amazing-feature`
4. Run tests: `./gradlew :core:testDebugUnitTest`
5. Push and open a Pull Request

---

## 📄 License

```
Copyright 2026 LibTermux Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0
```
