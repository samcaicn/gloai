# Laya prompt and calibration code (vendored)

The TypeScript files in this folder come from [mizchi/laya-mlx](https://github.com/mizchi/laya-mlx),
`web/packages/laya-web/src/` at commit `dc3aa6b150cb861d0788fbd421cfd1303de4ed57`, licensed under the Apache License 2.0
(<https://www.apache.org/licenses/LICENSE-2.0>). They turn a state and typed questions into Laya's token sequences and
turn the model's logits into calibrated answers, byte for byte like Python `Agent.predict`.

Not vendored: `session.ts` (browser fetch and onnxruntime-web). mu runs the graph with onnxruntime-node instead, in
`../runner.ts`. Local changes are marked `mu:` in the files.

The upstream NOTICE:

```text
laya-mlx
Copyright 2026 laya-mlx contributors

This product includes software derived from Laya:
https://github.com/NandhaKishorM/laya
Copyright Convai Innovations and Laya contributors. Licensed under Apache-2.0.
Upstream source revision: 6a5819129eb220570792e417e49723d697efd76f

The token sequence construction, question rendering, confidence calculation,
presets, email utilities and language router are adapted from Laya.
The neural network is reimplemented using Apple's MLX, following Laya's
DecisionModel and the ModernBERT architecture in Hugging Face Transformers.
Model weights are downloaded separately from Convai Innovations on Hugging Face;
they are not included in this repository.
```

The model itself (`mizchi/laya-multilingual-onnx` on Hugging Face, Apache-2.0, an export of
`convaiinnovations/laya-multilingual`) is not part of mu: the person downloads it.
