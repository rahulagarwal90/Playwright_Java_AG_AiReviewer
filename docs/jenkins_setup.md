# Jenkins Setup & Configuration Guide

This guide provides step-by-step instructions for installing Jenkins, configuring the Playwright Java framework, and setting up automated Pull Request (PR) triggers.

## 1. Installation Guide (Windows)

1. **Download Jenkins**:
   - Go to the [Jenkins Download Page](https://www.jenkins.io/download/).
   - Download the **Windows (LTS)** installer (.msi).
2. **Install**:
   - Run the installer.
   - **Service Logon Credentials**: Use "Run service as LocalSystem" (simplest for local setup) or provide specific account details.
   - **Port Selection**: Default is `8080`. Ensure it's not in use.
3. **Unlock Jenkins**:
   - Open `http://localhost:8080` in your browser.
   - Retrieve the initial admin password from `C:\ProgramData\Jenkins\.jenkins\secrets\initialAdminPassword`.
4. **Install Plugins**:
   - Select "Install suggested plugins."

---

## 2. Global Tool Configuration

1. Go to **Manage Jenkins** > **Tools**.
2. **JDK**: Name = `JDK 17`, Path = your JDK folder.
3. **Maven**: Name = `Maven 3.8`, click "Install automatically".
4. **Allure**: Name = `Allure`, click "Install automatically" from Maven Central.

---

## 3. Creating the Pipeline Job

1. Click **New Item** > Name it `Playwright-Java-Antigravity-Framework-Pipeline`.
2. Choose **Multibranch Pipeline**.
3. **Branch Sources**: Add **GitHub**.
4. **Repository URL**: `https://github.com/rahulagarwal90/Playwright_Java_Antigravity_Framework`.
5. **Credentials**: See Step 4 below.

---

## 4. GitHub Server Configuration (For Status Checkmarks)

1. Go to **Manage Jenkins** > **System** > **GitHub**.
2. **Add GitHub Server**:
   - **API URL**: `https://api.github.com`
   - **Credentials**: Click **Add** > **Secret text**.
   - **Secret**: Paste your GitHub PAT.
   - **ID**: `github-token`.
3. **Test Connection**: Ensure it says "verified".

---

## 5. Pull Request (PR) Workflow Setup

### A. GitHub Webhook
1. Go to GitHub Repo > **Settings** > **Webhooks** > **Add webhook**.
2. Payload URL: `http://<your-public-ip-or-ngrok-url>/github-webhook/`.
   - *Note: If on `localhost`, GitHub cannot "see" you without a tool like **ngrok**.*

### B. Branch Protection (The Gatekeeper)
1. Go to GitHub > **Settings** > **Branches** > **Add branch protection rule**.
2. **Branch pattern**: `main`.
3. Check: **"Require a pull request before merging"**.
5. **How to find the Status Checks**:
   - Type `jenkins` and wait for the dropdown.
   - **REQUIRED**: Select both `continuous-integration/jenkins/branch` AND `continuous-integration/jenkins/pr-head`.
   - *Note: If you don't see them, run the Jenkins job manually once.*

### C. Optional: Webhooks via ngrok
If you want **instant** triggers (instead of waiting for the 5-min poll):
1. Download [ngrok](https://ngrok.com/).
2. Run: `ngrok http 8080`.
3. Copy the `https://...` URL and use it in Step 5A above.

---

## 6. Nightly Scheduling (Automated Runs)

The framework runs a full suite every night at **2:00 AM**.
- In Jenkins, these will show as **"Started by timer"**.

---

## 7. Localhost Automation (No-Click Sync)

Since you are running on `localhost`, Jenkins normally can't "hear" GitHub. We have two ways to fix this:

### A. Automatic Polling (Already in Jenkinsfile)
The `Jenkinsfile` is now set to `pollSCM('H/5 * * * *')`. 
- Every 5 minutes, Jenkins will "ask" GitHub if there is new code on the current branch.

### B. Discovering New Branches & PRs
To make Jenkins "find" your new branches automatically:
1. Go to your Job Dashboard > **Configure**.
2. Scroll to **Scan Multibranch Pipeline Triggers**.
3. Check: **"Periodically if not otherwise run"**.
4. Interval: **1 minute** or **2 minutes**.
5. **Save**.
   - *Result: Jenkins will now automatically scan for new PRs/Branches without you clicking "Scan".*

> [!TIP]
> **Why both?** Section B is the "Recruiter" (it finds new branches). Section A is the "Upkeep" (it checks existing branches). While slightly redundant, having both ensures maximum reliability on localhost!

---

## 8. Monitoring & Custom Builds

### A. Build with Parameters
If you have fixed your credentials, you will see **"Build with Parameters"** in the left menu.
- Select your **Browser** (Chrome/Firefox/Webkit).
- Enter your **Tags** (e.g., `@SauceDemo`).

### B. Viewing Results
- **Logs**: Click Build # > **Console Output**.
- **Reports**: Click the **Allure Report** icon.
- **Videos/Traces**: Click Build # > **Last Successful Artifacts**.

---

## 9. Troubleshooting: Bad Credentials (401 Error)

If you see `org.kohsuke.github.HttpException: {"message": "Bad credentials"}`, follow this:

1. **Regenerate PAT**: Go to GitHub > Developer Settings > Tokens (classic). Ensure `repo` and `admin:repo_hook` are checked.
2. **Update Job Credentials**: 
   - Go to Job > **Configure** > **Branch Sources**.
   - Select/Create a **"Username with password"** type.
   - **Username**: Your GitHub ID.
   - **Password**: The NEW Personal Access Token.
3. **Update System Credentials**:
   - Go to **Manage Jenkins** > **System** > **GitHub**.
   - Use the same new Token as a **"Secret text"** type.

---

## 10. Troubleshooting: Password Management (Localhost)

If Chrome won't save your `localhost:8080` password, use the **Hosts Workaround**:
1. Edit `C:\Windows\System32\drivers\etc\hosts`.
2. Add: `127.0.0.1  jenkins.local`
3. Access Jenkins via `http://jenkins.local:8080`.

---

## 11. Navigating the Jenkins UI: Cheat Sheet

| To see this... | Go here... |
| :--- | :--- |
| **Real-time Logs** | Build # > **Console Output** |
| **Allure Dashboard** | Main job page > **Allure Report** icon |
| **Videos & Traces** | Build # > **Last Successful Artifacts** |
| **Trend Graphs** | Main job page > **Test Result Trend** |
| **Custom Run** | **Build with Parameters** |

---

## 12. Cleaning up Old Branches

When you merge a PR and delete the branch on GitHub, Jenkins will clean up automatically:
1. **GitHub**: Click "Delete Branch" after merging.
2. **Jenkins**: Click "Scan Multibranch Pipeline Now" on the job dashboard.
3. Jenkins will move the deleted branch to "Orphaned Items" and eventually delete it based on the job configuration.
