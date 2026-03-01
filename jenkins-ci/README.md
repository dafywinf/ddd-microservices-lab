# Jenkins CI — Local Environment

A self-contained Jenkins LTS (JDK 21) setup that mirrors the `inventory-service-ci` GitHub Actions workflow.

## Prerequisites

- Docker & Docker Compose installed
- Port `8080` free on your machine

## Start Jenkins

```bash
cd jenkins-ci
docker compose up -d
```

Jenkins will be available at <http://localhost:9090>.

## Retrieve the Initial Admin Password

```bash
docker exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword
```

Paste the output into the **Unlock Jenkins** screen in your browser.

## First-Time Setup

See **[docs/first-time-setup.md](docs/first-time-setup.md)** for the full step-by-step guide covering plugin installation, admin user creation, and pipeline job configuration.

## Maven Dependency Cache

The pipeline maps `$HOME/.m2` on the Jenkins host into the Maven container at `/var/maven/.m2`. Dependencies are downloaded once and reused across builds without any extra configuration.

## Allure Reporting

The `Allure Report` stage is a placeholder. Once the **Allure** plugin is installed:

1. Uncomment the `allure` step inside `Jenkinsfile`.
2. Remove the `echo` placeholder line.
3. Allure results are read from `inventory-service/target/allure-results`.

## Stop Jenkins

```bash
docker compose down
```

Add `-v` to also remove the `jenkins_home` volume (resets all Jenkins data):

```bash
docker compose down -v
```