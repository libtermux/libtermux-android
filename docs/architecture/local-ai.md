# Local AI: extension points (design notes)

**Status: not implemented. No AI dependency is present in any module.**

This describes where a future, *optional* `aeonruntime-ai` module would attach. It deliberately adds no code
and no dependencies now.

## Principles

- Independent of the core runtime: `:core` must never depend on it.
- Offline-first where practical; no implicit network calls.
- Capabilities are reported honestly. Having a Linux environment does **not** imply GPU/NPU acceleration.

## Proposed structure (when implemented)

```
ai/
├── model/          storage, versions, integrity, lifecycle (download, verify, delete)
├── inference/      backend interface + CPU backend first
├── acceleration/   optional GPU/NPU bridges, each probed and verified per device
├── resource/       memory budget, battery state, thermal state
└── integration/    glue to :core (e.g. running a backend inside the runtime environment)
```

## Backend candidates (to evaluate, none committed)

llama.cpp, ONNX Runtime, MLC LLM, ExecuTorch. Selection criteria: quantization formats supported, Android ABI
coverage, binary size, licensing, and measured performance on target devices.

## Requirements to design for

Model management and lifecycle; CPU inference baseline; optional acceleration bridges; memory management;
thermal- and battery-aware scheduling; quantization compatibility; offline operation.

## Sustainability

Goals: avoid unnecessary cloud inference, run efficiently, schedule work with battery/thermal awareness.
These are **measurable goals**. Local execution is not always cheaper in energy than cloud execution, so any
claim must come with measurements (energy per task, device and model stated).
