# Local Test Architecture Configuration

1. Install Ollama
2. Run the following command in your terminal:
   ```bash
   ollama run qwen2.5-coder:7b

   Step 1: Verification
	1.	Ensure your terminal has Ollama running (ollama run qwen2.5-coder:7b).
	2.	Open your project in VS Code.
	3.	Make sure systemPatterns.md is saved in the root folder.
Step 2: Open Continue Chat
	1.	Press Cmd + L to open the Continue sidebar.
	2.	Ensure Local Qwen Coder is selected at the bottom.
Step 3: Paste the Full Detailed Master Prompt
Type @codebase first, then paste your exact prompt block into the chat box and press Enter.
Continue Main Configuration (config.yaml)
name: Local Test Architecture Config
version: 0.0.1
schema: v1
models:
  - name: qwen2.5-coder:7b
    provider: ollama
    model: qwen2.5-coder:7b
    title: Local Qwen Coder
    roles:
      - chat
      - edit
      - apply
      - autocomplete

Cline Configuration Guardrails
Before pasting the prompt, double-check that your Cline settings look exactly like this so that your local model doesn't run into automated timeout walls:
•	API Provider: Ollama
•	Base URL: http://localhost:11434
•	Model: qwen2.5-coder:7b
•	Context Window: 32768
•	Timeout: 60000 (Crucial to give your Mac M5 Pro 60 seconds to process terminal/file activities without killing the task)

3. **Save the file** (`Cmd + S`). 

Now it will render beautifully as a standard, clean markdown document in VS Code! For future files, it's always safest to create and edit them directly inside your VS Code window to keep them completely plain-text.