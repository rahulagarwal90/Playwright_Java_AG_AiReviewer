# Ollama Guide for Playwright_Java_AG_AiReviewer

This file explains how to install, manage, and use Ollama on macOS for local AI model execution in this repository.
It includes commands for starting and stopping Ollama, inspecting models, deleting models, choosing the right model, and understanding key AI terms.

## 1. What Ollama Is

Ollama is a local language-model runner that lets you download, manage, and run AI models on your Mac.
It is useful for local AI code review, automation guidance, and offline model execution without sending your code to the cloud.

This repo uses Ollama mainly to support AI-assisted review and local developer workflows in `ai-reviewer`.

## 2. Key Concepts

### Tokens
* Tokens are the smallest pieces of text a model processes.
* Roughly, `1 token ≈ 0.75 words` for everyday text.
* For English prose, one token is typically around `4 characters` on average.
* That means `0.75 words` is roughly `3 to 5 characters`, depending on word length and punctuation.
* Code uses more tokens per word because punctuation, identifiers, and symbols are split into smaller units.
* Example: a Java method and code snippet often consume many tokens quickly.

Why it matters:
* Every prompt, code snippet, or repo excerpt consumes tokens.
* Larger inputs mean more token usage and slower responses.
* Longer prompts also leave fewer tokens available for the model's output.

### Context Window
* The context window is the maximum number of tokens a model can keep in memory at once.
* It includes:
  * the system prompt,
  * the user prompt,
  * the model's own output.
* If the combined token count exceeds the model limit, earlier content is dropped.

Impact:
* Long source files, big diffs, or verbose prompts can cause the model to lose earlier context.
* If the model forgets critical instructions, results become less accurate.
* **Active Framework Optimization:** Our framework explicitly overrides Ollama's default 4k threshold by configuring a `16,384` token window (`num_ctx`) directly inside the Java JSON parameters to prevent truncation bugs.

### Context Window Management
* In Ollama, the context window size is determined by the model architecture and cannot be directly changed for a specific model.
* Some models support larger native windows, while others have shorter limits.
* If you need more usable context:
  * choose a model with a larger native window,
  * send only the most relevant snippets,
  * use retrieval-based prompts or chunk your data,
  * combine shorter sections rather than sending very large files at once.
* Ollama's `--truncate` option exists only for embedding models and controls whether inputs exceeding the context limit are truncated or rejected.
* For normal text models, if the prompt exceeds the model limit, the model will stop using older tokens.

Example:
* `qwen2.5-coder:7b` and `qwen3-coder:14b` have default 4,096-token windows for most use cases.
* `llama3.1:8b` supports an 8,192-token window in many configurations.

### RAG (Retrieval-Augmented Generation)
* RAG means the model augments its prompt with relevant external information at runtime.
* It does not retrain the model.
* Instead, it retrieves specific data from local files, docs, or code and feeds that to the model.

Why it helps:
* It reduces the need to send the full repository to the model.
* The model can work from relevant snippets only, which improves accuracy and reduces token usage.

### MCP and Local Integrations
* MCP stands for Model Context Protocol or Model Context Pipeline in this environment.
* It is the way local tools and VS Code AI assistants manage conversation history, prompt context, and model interactions.
* Ollama is the model runner; MCP-style tools may sit above Ollama and provide the prompt/response workflow.
* For basic Ollama commands, you can ignore MCP. If you use an editor AI integration, it may use a similar context layer behind the scenes.

### Contextual Limitation Summary
* Smaller models are faster and use less RAM, but may hallucinate or make mistakes.
* Larger models are more accurate for complex code reasoning, but use more memory and run slower.
* Always choose the right model for the task: planning, code review, or developer guidance.

## 3. Installation on macOS

### Install Ollama
1. Download and install Ollama from:
   https://ollama.com/download/mac
2. Confirm installation:
   ```bash
   ollama --version
   ```

### Where Ollama Lives on macOS
* Application bundle: `/Applications/Ollama.app`
* Local Ollama directory: `~/.ollama/`
* Model weights location: `~/.ollama/models/`

Hidden files note:
* On macOS, directories that begin with a dot (`.`) are hidden by default.
* To show hidden files in Finder, press `Cmd+Shift+.` while a Finder window is open.
* To view hidden files in the terminal, use:
  ```bash
  ls -la ~/.ollama/models/
  ```

Useful command to inspect models:
```bash
open ~/.ollama/models
```

## 4. Ollama Command Reference

### Check installed models
```bash
ollama list
```

### Show details for a specific model
```bash
ollama show qwen2.5-coder:7b
```

### Pull / download a model
```bash
ollama pull qwen2.5-coder:7b
```

### Run a specific model version
```bash
ollama run qwen2.5-coder:7b
```

Run a version-specific model directly:
```bash
ollama run qwen3-coder:14b
```

Run with a prompt:
```bash
ollama run qwen2.5-coder:7b --prompt "Review this Java method for Playwright best practices."
```

### Start the Ollama service
```bash
ollama serve
```

This starts the Ollama background service and makes it available for CLI or API use.

### See running models
```bash
ollama ps
```

### Stop a running model
```bash
ollama stop qwen2.5-coder:7b
```

If you have multiple models running, use `ollama ps` first, then stop each model.

### Delete a model
```bash
ollama rm qwen2.5-coder:7b
```

Remove all local models safely by listing and deleting them:
```bash
ollama list | awk 'NR>1 {print $1}' | xargs -I{} ollama rm {}
```

> Warning: deleting a model removes the downloaded weights permanently.

### Copy or rename a model
```bash
ollama cp qwen2.5-coder:7b qwen2.5-coder-copy:7b
```

## 5. Recommended Models and Limitations

### Best models for this repository
| Task | Recommended model | Why | Limitation |
| --- | --- | --- | --- |
| Code review and code generation | `qwen2.5-coder:14b` | Best accuracy, precise line math calculations, and code reasoning. | Uses more RAM (~9GB VRAM footprint). |
| Fast local review or quick prompts | `qwen2.5-coder:7b` | Good speed with reasonable accuracy for lightweight checks. | More likely to hallucinate on large context windows. |
| High-level design, architecture, or summary | `llama3.1:8b` | Great for planning, design documentation, and structural explanations. | Less precise for exact code execution output lines. |

### Choosing the right model
* Use `qwen3-coder:14b` for the strongest code reasoning and best chance of correct edits.
* Use `qwen2.5-coder:7b` when you want speed and can work with smaller context.
* Use `llama3.1:8b` for design notes, architecture, and summaries rather than code change.

### What impacts model quality
* Token budget and prompt length.
* Model architecture and size.
* Local system memory and CPU usage.
* Whether you send targeted code snippets or entire files.

### How to choose context window usage
* Prefer models with larger native windows if you need more context.
* If a model’s window is not enough:
  * break the text into smaller chunks,
  * send only the most relevant code or diff portions,
  * use retrieval-style prompts that reference external files,
  * summarize or preprocess large inputs before sending them.
* Ollama itself does not let you manually increase a model’s native context window; the limit is model-specific.

## 6. Working with a Specific Model Version

### Start a model version explicitly
```bash
ollama pull qwen3-coder:14b
ollama run qwen3-coder:14b
```

### Run a prompt with a specific version
```bash
ollama run qwen3-coder:14b --prompt "Explain the best practice for Playwright selectors in Java."
```

### If you want an always-on service
```bash
ollama serve
```

Then use `ollama run` or `ollama ps` as needed.

## 7. Useful Mac Commands and Model Location

Inspect the model folder:
```bash
ls -la ~/.ollama/models/
```

Show active service port if needed:
```bash
lsof -i :11434
```

If the `ollama` CLI is not found, make sure `/Applications/Ollama.app` is installed and your shell path includes Ollama.

## 8. Troubleshooting

### `ollama: command not found`
* Ensure Ollama is installed from `/Applications/Ollama.app`.
* Restart your terminal after installation.

### Model pull fails
* Check your network connection.
* Verify the model name and version.

### Running out of memory
* Stop unused models with `ollama stop`.
* Use a smaller model like `qwen2.5-coder:7b`.
* Delete unused models with `ollama rm`.

### Model performance is slow
* Close other running models.
* Use smaller prompts.
* Choose a lower-memory model version.

## 9. Quick Start Checklist

1. Install Ollama on macOS.
2. Pull a model: `ollama pull qwen2.5-coder:7b`.
3. Run the model: `ollama run qwen2.5-coder:7b`.
4. Inspect models: `ollama list` and `ollama show <model>`.
5. Stop or delete when finished: `ollama stop <model>` or `ollama rm <model>`.

## 10. Summary

This file gives you a complete Ollama reference for:
* starting and stopping Ollama,
* running specific model versions,
* locating models on macOS,
* checking installed models,
* deleting models,
* understanding tokens, context windows, and RAG,
* choosing the best model for your use case.

Use `Ollama_Readme.md` as your primary guide whenever you manage local models for this repo.
