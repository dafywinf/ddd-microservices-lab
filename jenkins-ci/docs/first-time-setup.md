# Jenkins First-Time Setup

Follow these steps in order the first time you start the Jenkins container.

---

## 1. Start the Container

```bash
cd jenkins-ci
docker compose up -d
```

Wait ~30 seconds for Jenkins to finish initialising, then open <http://localhost:9090>.

---

## 2. Unlock Jenkins

Retrieve the one-time admin password:

```bash
docker exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword
```

Paste the output into the **Administrator password** field and click **Continue**.

---

## 3. Install Plugins

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

## 4. Create the Admin User

After the restart you will be prompted to create your first admin user. Fill in:

- Username
- Password
- Full name
- Email address

Click **Save and Continue**, then **Save and Finish**.

---

## 5. Configure the Jenkins URL

Accept the default on the **Instance Configuration** screen (update it to `http://localhost:9090/` if Jenkins pre-fills 8080) and click **Save and Finish → Start using Jenkins**.

---

## 6. Configure a Global Maven Tool (optional but recommended)

**Manage Jenkins → Tools → Maven installations → Add Maven**

| Field | Value |
|---|---|
| Name | `Maven 3` |
| Install automatically | checked |
| Version | `3.9.6` |

Click **Save**. The pipeline uses a Docker Maven agent so this is not strictly required, but it is useful if you add freestyle jobs later.

---

## 7. Create the Pipeline Jobs

Repeat these steps for each service. The only difference between them is the **name** and **Script Path**.

### 7a. inventory-service

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

### 7b. order-service

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

## 8. Run the First Builds

Click **Build Now** on each job in turn.

Open the build log (**Console Output**) and confirm:

- The Maven Docker image is pulled successfully.
- `mvn clean verify` runs inside the correct service directory.
- The build finishes with `BUILD SUCCESS`.

---

## Done

Jenkins is fully configured. Subsequent starts only require:

```bash
docker compose up -d
```