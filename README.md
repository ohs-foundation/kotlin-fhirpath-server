# kotlin-fhirpath-server

A Ktor-based server that evaluates [FHIRPath](https://hl7.org/fhirpath/) expressions against FHIR
resources. It implements the
[FHIRPath Lab Server Engine API](https://github.com/brianpos/fhirpath-lab/blob/master/server-api.md)
specification, supporting FHIR versions **R4**, **R4B**, and **R5**.

## Prerequisites

- **Java 21** (the project uses JVM toolchain 21)
- **Gradle** (wrapper included — no separate installation needed)

## API

The server exposes three FHIRPath evaluation endpoints — one per FHIR version — plus a health check:

|            Endpoint            | Method |                                 Description                                 |
|--------------------------------|--------|-----------------------------------------------------------------------------|
| `/`                            | GET    | API overview, endpoint listing, and server version                          |
| `/health`                      | GET    | Health check with current timestamp                                         |
| `/kotlin-fhirpath-config.json` | GET    | [FHIRPath Lab custom engine configuration][custom-config] for local testing |
| `/fhirpath-r4`                 | POST   | Evaluate a FHIRPath expression against an **R4** resource                   |
| `/fhirpath-r4b`                | POST   | Evaluate a FHIRPath expression against an **R4B** resource                  |
| `/fhirpath-r5`                 | POST   | Evaluate a FHIRPath expression against an **R5** resource                   |

[custom-config]: https://github.com/brianpos/fhirpath-lab/blob/develop/docs/custom-configuration.md

### Request

**Content-Type**: `application/fhir+json` or `application/json`

**Body**: A FHIR `Parameters` resource. See the
[input parameters definition](https://github.com/brianpos/fhirpath-lab/blob/master/server-api.md#input-parameters-resource)
for the full parameter specification. Support status in this implementation:

|      Parameter      | Supported |
|---------------------|:---------:|
| `expression`        |     ✅     |
| `resource`          |     ✅     |
| `context`           |     ✅     |
| `variables`         |     ✅     |
| `terminologyserver` |     ❌     |

**Example request body:**

```json
{
  "resourceType": "Parameters",
  "parameter": [
    {
      "name": "expression",
      "valueString": "name.family"
    },
    {
      "name": "resource",
      "resource": {
        "resourceType": "Patient",
        "name": [{ "family": "Smith", "given": ["John"] }]
      }
    }
  ]
}
```

### Response

A successful evaluation returns HTTP `200` with a FHIR `Parameters` resource containing the results
and debug trace information.

Validation errors return HTTP `400` with an `OperationOutcome`. Unexpected server errors return HTTP
`500` with an `OperationOutcome`.

## Versioning

The build derives the server version from `git describe --tags --always --dirty` (see
`build.gradle.kts`). The `/` endpoint reports this version. Every deployment therefore identifies
the release or commit that produced it.

The build removes the leading `v` from the tag name:

|         Build point          |                   Reported version                    |
|------------------------------|-------------------------------------------------------|
| On the exact tag `v1.2.3`    | `1.2.3`                                               |
| 4 commits after tag `v1.2.3` | `1.2.3-4-gabc1234` (commit count and abbreviated SHA) |
| Before the first tag exists  | The abbreviated commit SHA                            |
| With uncommitted changes     | The same value, plus the suffix `-dirty`              |
| When git is unavailable      | `0.0.0-unknown`                                       |

### Create a release

To release a new version, tag the commit and push the tag:

```bash
git tag v1.2.3
git push origin v1.2.3
```

Every later build reads the new tag, including the Docker build. You do not need to edit a version
number anywhere in the project. You can also turn the tag into a GitHub Release, but the version
does not depend on it.

## Deployment

### Local

Use the Gradle wrapper to build and run the server:

|          Task           |                          Description                          |
|-------------------------|---------------------------------------------------------------|
| `./gradlew run`         | Run the server locally                                        |
| `./gradlew test`        | Run the test suite                                            |
| `./gradlew build`       | Compile and assemble the project                              |
| `./gradlew buildFatJar` | Build a self-contained executable JAR (`fhirpath-server.jar`) |

The server starts on port `8080` by default. Set the `PORT` environment variable to override:

```bash
PORT=9090 ./gradlew run
```

When the server starts successfully you will see:

```
2024-12-04 14:32:45.584 [main] INFO  Application - Application started in 0.303 seconds.
2024-12-04 14:32:45.682 [main] INFO  Application - Responding at http://0.0.0.0:8080
```

#### Running the fat JAR directly

```bash
./gradlew buildFatJar

java -jar build/libs/fhirpath-server.jar
```

### Docker

A [Dockerfile](Dockerfile) and [docker-compose.yml](docker-compose.yml) are included. Build and run
with Docker Compose:

```bash
docker compose up --build
```

Or build and run the image directly:

```bash
docker build -t fhirpath-server .
docker run -p 8080:8080 fhirpath-server
```

The Ktor Gradle plugin also provides Docker tasks as an alternative to the Dockerfile:

|                  Task                   |                  Description                   |
|-----------------------------------------|------------------------------------------------|
| `./gradlew buildImage`                  | Build a Docker image from the fat JAR          |
| `./gradlew publishImageToLocalRegistry` | Publish the image to the local Docker registry |
| `./gradlew runDocker`                   | Build the image and run it as a container      |

### Application Server (Production)

The server is packaged as a Docker image and run on any host with Docker installed. Deployment is
currently manual — there is no CI/CD pipeline.

#### What you need before deploying

- Docker installed on the target host
- A container registry to push and pull images from (e.g. Docker Hub, GitHub Container Registry,
  GCP Artifact Registry)

#### Build and push the image

```bash
docker build -t <REGISTRY>/<IMAGE>:<TAG> .
docker push <REGISTRY>/<IMAGE>:<TAG>
```

#### Deploy to the host

On the target host, pull the image and start the container:

```bash
docker pull <REGISTRY>/<IMAGE>:<TAG>
docker run -d --restart unless-stopped \
  -p 8080:8080 \
  --name fhirpath-server \
  <REGISTRY>/<IMAGE>:<TAG>
```

Make sure the host's firewall allows inbound traffic on port `8080`.

#### Deploying an update

```bash
# 1. Build and push a new image
docker build -t <REGISTRY>/<IMAGE>:<TAG> .
docker push <REGISTRY>/<IMAGE>:<TAG>

# 2. Pull and restart on the host
docker pull <REGISTRY>/<IMAGE>:<TAG>
docker stop fhirpath-server
docker rm fhirpath-server
docker run -d --restart unless-stopped -p 8080:8080 --name fhirpath-server <REGISTRY>/<IMAGE>:<TAG>
```

#### Deploying to a Google Cloud Compute Engine VM

Authenticate the Docker CLI against Artifact Registry, then use the commands above with your
registry path as `<REGISTRY>/<IMAGE>:<TAG>`:

```bash
gcloud auth login
gcloud config set project <PROJECT_ID>
gcloud auth configure-docker <REGION>-docker.pkg.dev
# registry path: <REGION>-docker.pkg.dev/<PROJECT_ID>/<REPO>/fhirpath-server:<TAG>
```

To run commands on the VM remotely:

```bash
# open a shell
gcloud compute ssh <INSTANCE_NAME> --zone <ZONE>

# or run a single command
gcloud compute ssh <INSTANCE_NAME> --zone <ZONE> --command "docker pull ..."
```

## Specification

This server implements the
[FHIRPath Lab Server Engine API](https://github.com/brianpos/fhirpath-lab/blob/master/server-api.md).

Once deployed, one would be able to point [FHIRPath Lab](https://fhirpath-lab.com) to this server to use as an
evaluation engine.
