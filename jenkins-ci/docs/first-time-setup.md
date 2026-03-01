# Jenkins First-Time Setup

Follow these steps in order the first time you start the Jenkins container.

---

## 1. Generate the SSH Key Pair

The agent authenticates to the controller via SSH. Generate a dedicated key pair (do **not** reuse an existing key):

```bash
ssh-keygen -t rsa -b 4096 -f ~/.ssh/jenkins_agent -N ""
```

This creates:

- `~/.ssh/jenkins_agent` — private key (stays on your machine, added to Jenkins credentials)
- `~/.ssh/jenkins_agent.pub` — public key (given to the agent container)

---

## 2. Create the `.env` File

```bash
cd jenkins-ci
cp .env.example .env
```

Replace the placeholder in `.env` with the actual public key:

```bash
echo "JENKINS_AGENT_SSH_PUBKEY=$(cat ~/.ssh/jenkins_agent.pub)" > .env
```

---

## 3. Start the Containers

```bash
docker compose up -d
```

This starts two containers:

| Container | Role |
|---|---|
| `jenkins-lts` | Controller — manages jobs, UI, configuration |
| `jenkins-agent` | Worker — executes all builds via SSH |

Wait ~30 seconds, then open <http://localhost:18080>.

---

## 4. Unlock Jenkins

Retrieve the one-time admin password:

```bash
docker exec jenkins-lts cat /var/jenkins_home/secrets/initialAdminPassword
```

Paste the output into the **Administrator password** field and click **Continue**.

---

## 5. Install Plugins

On the **Customize Jenkins** screen choose **Install suggested plugins**.

Wait for the installation to complete (this may take a few minutes).

Once the suggested plugins are installed, go to:

**Manage Jenkins → Plugins → Available plugins**

Search for and install each of the following:

| Plugin | Search term |
|---|---|
| Docker Pipeline | `Docker Pipeline` |
| Allure | `Allure` |

Tick both, click **Install**, and check **Restart Jenkins when installation is complete and no jobs are running**.

---

## 6. Create the Admin User

After the restart you will be prompted to create your first admin user. Fill in:

- Username
- Password
- Full name
- Email address

Click **Save and Continue**, then **Save and Finish**.

---

## 7. Configure the Jenkins URL

Accept the default on the **Instance Configuration** screen (update it to `http://localhost:18080/` if Jenkins pre-fills 8080) and click **Save and Finish → Start using Jenkins**.

---

## 8. Disable the Built-In Node

> **Security:** The controller must never run build jobs. Running builds on the controller gives pipeline scripts full access to `jenkins_home`, credentials, and Jenkins internals.

**Manage Jenkins → Nodes → Built-In Node → Configure**

| Field | Value |
|---|---|
| Number of executors | `0` |
| Usage | `Only build jobs with label expressions matching this node` |

Click **Save**.

---

## 9. Add the SSH Agent Node

**Manage Jenkins → Nodes → New Node**

- Name: `docker-agent`
- Type: **Permanent Agent**
- Click **Create**

Fill in the node configuration:

| Field | Value |
|---|---|
| Remote root directory | `/home/jenkins/agent` |
| Labels | `maven-builds` |
| Launch method | `Launch agents via SSH` |
| Host | `jenkins-agent` |
| Host Key Verification Strategy | `Non verifying Verification Strategy` |

Under **Credentials** click **Add → Jenkins**:

| Field | Value |
|---|---|
| Kind | `SSH Username with private key` |
| Username | `jenkins` |
| Private Key | **Enter directly** — paste contents of `~/.ssh/jenkins_agent` |

Click **Add**, select the new credential, then click **Save**.

Jenkins will connect to the agent automatically. Verify it comes online under **Manage Jenkins → Nodes** — the agent should show 0 pending executors and no red X.

---

## 10. Configure a Global Maven Tool (optional)

**Manage Jenkins → Tools → Maven installations → Add Maven**

| Field | Value |
|---|---|
| Name | `Maven 3` |
| Install automatically | checked |
| Version | `3.9.6` |

Click **Save**. The pipeline uses a Docker Maven agent so this is not strictly required, but it is useful if you add freestyle jobs later.

---

## 11. Create the Pipeline Jobs

Repeat these steps for each service. The only difference is the **name** and **Script Path**.

### 11a. inventory-service

1. From the dashboard click **New Item**.
2. Enter the name `inventory-service`, select **Pipeline**, and click **OK**.
3. Under **Build Triggers** you can optionally enable **Poll SCM** or a webhook later.
4. Scroll to **Pipeline** and set:

   | Field | Value |
   |---|---|
   | Definition | `Pipeline script from SCM` |
   | SCM | `Git` |
   | Repository URL | your local or remote repo URL |
   | Branch | `*/main` (or your working branch) |
   | Script Path | `inventory-service/Jenkinsfile` |

5. Click **Save**.

### 11b. order-service

1. From the dashboard click **New Item**.
2. Enter the name `order-service`, select **Pipeline**, and click **OK**.
3. Under **Build Triggers** you can optionally enable **Poll SCM** or a webhook later.
4. Scroll to **Pipeline** and set:

   | Field | Value |
   |---|---|
   | Definition | `Pipeline script from SCM` |
   | SCM | `Git` |
   | Repository URL | your local or remote repo URL |
   | Branch | `*/main` (or your working branch) |
   | Script Path | `order-service/Jenkinsfile` |

5. Click **Save**.

---

## 12. Run the First Builds

Click **Build Now** on each job in turn.

Open the build log (**Console Output**) and confirm:

- The build is assigned to `docker-agent` (not `Built-In Node`).
- The Maven Docker image is pulled successfully.
- `mvn clean verify` runs inside the correct service directory.
- The build finishes with `BUILD SUCCESS`.

---

## Done

Jenkins is fully configured. Subsequent starts only require:

```bash
docker compose up -d
```
