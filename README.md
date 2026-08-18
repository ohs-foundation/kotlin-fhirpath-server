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
| `variables`         |  partial  |
| `terminologyserver` |     ❌     |

- `resource` accepts the resource inline, or in a `json-value` extension. The server rejects the
  `xml-value` form, because it reads JSON only.
- `variables` accepts the FHIR primitive types that map onto a FHIRPath type, and `valueQuantity`.
  It rejects the other types, such as `valueHumanName`, with a `400`. The error message names the
  type it rejected. A variable with no `value[x]` binds to empty.
- `variables` can repeat, and the server merges the parts of every occurrence. The other parameters
  must not repeat. Two parts that bind the same variable name are rejected with a `400`.
- The server accepts `terminologyserver`, echoes it back, and then ignores it. The engine has no
  terminology functions yet.

The server binds three standard variables for every evaluation:

|    Variable     |                            Value                             |
|-----------------|--------------------------------------------------------------|
| `%resource`     | The resource from the `resource` parameter                   |
| `%rootResource` | The same resource                                            |
| `%context`      | Each item the `context` expression returned, or the resource |

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

A successful evaluation returns HTTP `200` with a FHIR `Parameters` resource. The response contains
the evaluation results, the input parameters, and the output of any `trace()` calls. Each traced
value carries a `resource-path` extension naming where in the test resource it came from.

The server does not produce the optional `parseDebugTree`, `parseDebug`, `expectedReturnType`, or
`debug-trace` output. The engine exposes no AST or step trace yet, and the engine configuration
declares `supportsAST: false`.

A failure returns an `OperationOutcome`. The issue code identifies the cause:

| Status |  Issue code  |                          Cause                          |
|:------:|--------------|---------------------------------------------------------|
| `400`  | `structure`  | The request body is not a JSON object                   |
| `400`  | `required`   | A required parameter is absent                          |
| `400`  | `invalid`    | A parameter holds a value the server cannot use         |
| `400`  | `processing` | The FHIRPath expression failed to parse or to evaluate  |
| `500`  | `exception`  | The server failed for a reason unrelated to the request |

A fault in the submitted expression is a request error. It returns `400` and not `500`.

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

Every later build reads the new tag. You do not need to edit a version number anywhere in the
project. You can also turn the tag into a GitHub Release, but the version does not depend on it.

The Docker build is the exception. Its build context has no git history, so it cannot run
`git describe` and reports `0.0.0-unknown` unless the caller passes the version in — see
[Docker](#docker).

### Engine version

The `evaluator` output parameter reports the FHIRPath engine version, not the server version. This
is the version of the `fhir-path` library that `gradle/libs.versions.toml` declares. The build
writes it into the jar beside the server version:

```
Kotlin FHIRPath 1.0.0-beta05 (R4)
```

The FHIRPath Lab API requires this format: engine name, engine version, then FHIR version in
brackets. The two versions move independently. A server release can keep the same engine, and an
engine upgrade changes only this value.

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
export VERSION="$(git describe --tags --always --dirty | sed 's/^v//')"
docker compose up --build
```

Or build and run the image directly:

```bash
docker build --build-arg VERSION="$(git describe --tags --always --dirty | sed 's/^v//')" \
  -t fhirpath-server .
docker run -p 8080:8080 fhirpath-server
```

The `VERSION` argument is what the image reports at `/`. The build context carries no git history,
so omitting it yields `0.0.0-unknown`.

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
