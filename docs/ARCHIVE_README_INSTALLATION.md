# Master Installation Guide (`README_INSTALLATION.md`)

This guide provides comprehensive, step-by-step instructions to set up the runtime dependencies, build lifecycle tools, local AI engine, Jenkins CI server, and public webhook tunnel for the **Playwright Java Antigravity Framework**.

After installation is complete, use [README_IMPLEMENTATION_AND_RUNNING.md](README_IMPLEMENTATION_AND_RUNNING.md) for daily start/run/stop operations.

---

## 📋 Table of Contents
1. [OpenJDK 21 Installation](#1-openjdk-21-installation)
2. [Apache Maven 3.9+ Installation](#2-apache-maven-39-installation)
3. [Local AI Runtime (Ollama) Setup](#3-local-ai-runtime-ollama-setup)
4. [Jenkins CI Server Setup](#4-jenkins-ci-server-setup)
5. [Jenkins Global Tool Configurations](#5-jenkins-global-tool-configurations)
6. [Jenkins Credentials Store Setup](#6-jenkins-credentials-store-setup)
7. [Web Tunnel Gateway (ngrok) & GitHub Webhook Setup](#7-web-tunnel-gateway-ngrok--github-webhook-setup)

---

## 1. OpenJDK 21 Installation

### 🍎 Option A: macOS (Terminal)
#### Using Homebrew (Recommended)
1. Install OpenJDK 21:
   ```bash
   brew install openjdk@21
   ```
2. Symlink the JDK so the system Java wrapper can find it:
   ```bash
   sudo ln -sfn /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-21.jdk
   ```
3. Set environment variables in your shell configuration profile (e.g., `~/.zshrc`):
   ```bash
   export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
   export PATH="$JAVA_HOME/bin:$PATH"
   ```
4. Reload the profile:
   ```bash
   source ~/.zshrc
   ```

#### Manual Tarball Installation
1. Download the OpenJDK 21 `.tar.gz` for macOS (aarch64 or x64) from [Adoptium Temurin](https://adoptium.net/).
2. Extract the archive into `/Library/Java/JavaVirtualMachines/`:
   ```bash
   sudo tar -xzf OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.1.tar.gz -C /Library/Java/JavaVirtualMachines/
   ```
3. Configure `JAVA_HOME` pointing to `/Library/Java/JavaVirtualMachines/jdk-21.0.1+12/Contents/Home` in your `~/.zshrc`.

---

### 🪟 Option B: Windows (PowerShell)
#### Using Winget (Recommended)
1. Install Microsoft OpenJDK 21:
   ```powershell
   winget install Microsoft.OpenJDK.21
   ```
2. Close and reopen PowerShell to reload environment variables.

#### Manual MSI / ZIP Installation
1. Download the OpenJDK 21 Windows `.msi` or `.zip` installer from [Adoptium Temurin](https://adoptium.net/).
2. Run the `.msi` installer (it automatically configures environment variables), or extract the `.zip` archive to `C:\Program Files\Java\jdk-21\`.
3. Set user/system environment variables manually:
   - Click Start, search **"Edit the system environment variables"**, and click **Environment Variables**.
   - Under **System Variables**, click **New**:
     * Variable name: `JAVA_HOME`
     * Variable value: `C:\Program Files\Java\jdk-21` (or your extracted path)
   - Edit the **Path** variable under System Variables:
     * Add a new entry: `%JAVA_HOME%\bin`
4. Verify installation:
   ```powershell
   java -version
   ```

---

## 2. Apache Maven 3.9+ Installation

### 🍎 Option A: macOS (Terminal)
#### Using Homebrew (Recommended)
1. Install Apache Maven:
   ```bash
   brew install maven
   ```
2. Set environment variables in `~/.zshrc`:
   ```bash
   export MAVEN_HOME=/opt/homebrew/Cellar/maven/3.9.16/libexec
   export PATH="$MAVEN_HOME/bin:$PATH"
   ```
3. Reload:
   ```bash
   source ~/.zshrc
   ```

#### Manual Binary Tarball Installation
1. Download the binary `.tar.gz` file from the [Apache Maven Download Page](https://maven.apache.org/download.cgi).
2. Extract to a directory of your choice, e.g., `/usr/local/apache-maven`:
   ```bash
   sudo mkdir -p /usr/local/apache-maven
   sudo tar -xzf apache-maven-3.9.16-bin.tar.gz -C /usr/local/apache-maven
   ```
3. Configure `MAVEN_HOME` pointing to `/usr/local/apache-maven/apache-maven-3.9.16` and append `$MAVEN_HOME/bin` to your `PATH` in `~/.zshrc`.

---

### 🪟 Option B: Windows (PowerShell)
#### Using Winget (Recommended)
1. Install Apache Maven:
   ```powershell
   winget install Apache.Maven
   ```
2. Restart PowerShell to sync environment variables.

#### Manual ZIP Installation
1. Download the binary `.zip` file from the [Apache Maven Download Page](https://maven.apache.org/download.cgi).
2. Extract the zip to `C:\Program Files\Maven\apache-maven-3.9.16`.
3. Open **Environment Variables**:
   - Add new System Variable:
     * Variable name: `MAVEN_HOME`
     * Variable value: `C:\Program Files\Maven\apache-maven-3.9.16`
   - Edit the **Path** variable:
     * Add a new entry: `%MAVEN_HOME%\bin`
4. Verify installation:
   ```powershell
   mvn -version
   ```

---

## 3. Local AI Runtime (Ollama) Setup

The local AI Code Reviewer routes code quality validation queries through a localized Ollama service.

### Installation
- **macOS**: Download the [Ollama for Mac App](https://ollama.com/download) or install via Homebrew:
  ```bash
  brew install ollama
  ```
- **Windows**: Download the [Ollama for Windows Installer](https://ollama.com/download/windows) and execute the setup.

### Pull & Launch the Reviewer Model
Open your terminal/PowerShell and run:
```bash
# Pull the qwen2.5-coder:14b model (or run qwen2.5-coder:7b for lower RAM systems)
ollama pull qwen2.5-coder:14b

# Launch the model to verify service operation
ollama run qwen2.5-coder:14b
```
The service will automatically run an HTTP API server locally at `http://localhost:11434` for completions.

---

## 4. Jenkins CI Server Setup

### 🍎 Option A: macOS (Terminal)
#### Using Homebrew (Recommended)
1. Install Jenkins LTS:
   ```bash
   brew install jenkins-lts
   ```
2. Start the service:
   ```bash
   brew services start jenkins-lts
   ```
3. Access the dashboard at `http://localhost:8080` and unlock Jenkins using the initial administrator password located at:
   ```bash
   cat ~/Library/Application\ Support/Jenkins/secrets/initialAdminPassword
   ```

#### Standalone WAR Deployment
1. Download `jenkins.war` from the [Jenkins LTS release page](https://www.jenkins.io/download/).
2. Run via terminal:
   ```bash
   java -jar jenkins.war --httpPort=8080
   ```
3. Retrieve the unlock password from the terminal console logs or `~/.jenkins/secrets/initialAdminPassword`.

---

### 🪟 Option B: Windows (PowerShell)
#### Using MSI Installer
1. Download the Windows MSI Installer from the [Jenkins Download Page](https://www.jenkins.io/download/).
2. Run the installer and input local system service credentials (or configure it to run under the Local System account).
3. Access `http://localhost:8080` and unlock using the key at:
   ```powershell
   Get-Content "$env:ProgramData\Jenkins\.jenkins\secrets\initialAdminPassword"
   ```

#### Standalone WAR Deployment
1. Download `jenkins.war`.
2. Execute in PowerShell:
   ```powershell
   java -jar jenkins.war --httpPort=8080
   ```
3. Access `http://localhost:8080` and fetch the unlocking key from `C:\Users\<username>\.jenkins\secrets\initialAdminPassword`.

### Required Plugins Installation
From the Jenkins Dashboard, navigate to **Manage Jenkins** -> **Plugins** -> **Available plugins** and install:
1. **GitHub Branch Source** (Adds Multibranch Pipeline GitHub capabilities)
2. **Pipeline** (Core pipeline engine)
3. **Credentials** & **Plain Credentials** (Handles secret text storage)
4. **Allure Jenkins Plugin** (Generates browser test reporting dashboards)

---

## 5. Jenkins Global Tool Configurations

Map the local system paths to Jenkins global tool IDs to allow the pipelines to inject appropriate environments dynamically.

1. Navigate to **Manage Jenkins** -> **Tools**.
2. **Maven Installations**:
   - Click **Add Maven**.
   - **Name**: `Maven 3.9`
   - **MAVEN_HOME**:
     * *macOS*: `/opt/homebrew/Cellar/maven/3.9.16/libexec` (or manually extracted path `/usr/local/apache-maven/apache-maven-3.9.16`)
     * *Windows*: `C:\Program Files\Maven\apache-maven-3.9.16`
   - **Uncheck** "Install automatically".
3. **JDK Installations**:
   - Click **Add JDK**.
   - **Name**: `JDK 21` (or `JDK 17`)
   - **JAVA_HOME**:
     * *macOS*: `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` (or manually extracted path `/Library/Java/JavaVirtualMachines/jdk-21.0.1+12/Contents/Home`)
     * *Windows*: `C:\Program Files\Java\jdk-21`
   - **Uncheck** "Install automatically".
4. Click **Save**.

---

## 6. Jenkins Credentials Store Setup

The AI Code Reviewer uses a secure GitHub Personal Access Token (PAT) to comment on PR files.

1. Go to **Manage Jenkins** -> **Credentials** -> **System** -> **Global credentials (unrestricted)**.
2. Click **Add Credentials**.
3. **Kind**: `Secret text`
4. **Scope**: `Global (Jenkins, nodes, items, all child items, etc)`
5. **Secret**: Paste your GitHub PAT (Must have `repo` and `pull_request` scopes).
6. **ID**: `github-pr-token`
7. **Description**: `GitHub Token for AI Reviewer PR Comments`
8. Click **Create**.

---

## 7. Web Tunnel Gateway (ngrok) & GitHub Webhook Setup

Because GitHub hooks cannot hit `localhost:8080` directly, use `ngrok` to forward incoming event payloads safely.

### 🍎 macOS Setup (Terminal)
1. Install via Homebrew:
   ```bash
   brew install ngrok
   ```
2. Authenticate the gateway (retrieve your auth token from the [ngrok dashboard](https://dashboard.ngrok.com/)):
   ```bash
   ngrok config add-authtoken <YOUR_NGROK_AUTHTOKEN>
   ```
3. Start the forwarding tunnel on port 8080:
   ```bash
   ngrok http 8080
   ```

### 🪟 Windows Setup (PowerShell)
1. Install via Winget:
   ```powershell
   winget install ngrok
   ```
2. Close and reopen PowerShell, then authenticate:
   ```powershell
   ngrok config add-authtoken <YOUR_NGROK_AUTHTOKEN>
   ```
3. Start the tunnel:
   ```powershell
   ngrok http 8080
   ```

---

### GitHub Webhook Integration
1. Keep the `ngrok` terminal running. Copy the secure public HTTPS URL generated, e.g., `https://abcd-1234.ngrok-free.app`.
2. Go to your GitHub Repository -> **Settings** -> **Webhooks** -> **Add webhook**.
3. Configure the parameters:
   - **Payload URL**: `https://<your-ngrok-subdomain>.ngrok-free.app/github-webhook/` *(Make sure to append `/github-webhook/` at the end)*
   - **Content type**: `application/json`
   - **Secret**: *(Leave empty)*
   - **Which events would you like to trigger this webhook?**: Choose **Let me select individual events**, check **Pull requests**, and uncheck everything else.
4. Click **Add webhook**.
5. Check the webhook list and confirm there is a green checkmark indicating a successful delivery handshake.
