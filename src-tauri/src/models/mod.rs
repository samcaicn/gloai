// Copyright (c) 2026 MeeJoy

// tupAI P0 §3 — Model path management
//
// The model directory holds the `.gguf` (and friends) checkpoints the
// local model server loads. Two operations are the user-facing surface:
//   * `change_model_path` — move the existing files to a new directory
//     and update the persisted config pointer
//   * `scan_models`        — list everything in the active directory
//   * `delete_model`       — remove a single file (with safety checks)
//
// We don't move the files across filesystems (which would require a
// copy + delete) unless the user asks for it explicitly; cross-device
// moves fall back to copy + delete with a clear error if the copy
// fails halfway.

pub mod manager;
