# ValStats

ValStats is a serverless-oriented Valorant statistics application. The frontend renders cached player data immediately, while slower HenrikDev API synchronization updates DynamoDB separately.

This repository contains the Java/Micronaut backend. The React/Vite frontend lives in the sibling `../frontend` directory.

## Deployment status

The cached API works locally, but the production serverless migration is not complete yet.

- `api-lambda` can run locally with Netty and deploy behind API Gateway as a
  Micronaut payload-v2 Lambda. In AWS, match refresh requests are sent to SQS.
- `match-sync-lambda` contains the SQS Lambda handler used for queued match
  refreshes. The old `LocalRunner` remains a local-only utility.
- `HenrikApiRequestQueue` coordinates only one JVM. It cannot enforce a global Henrik API limit across scaled Lambda instances.
- The Java CDK module defines DynamoDB, SQS, Secrets Manager, both JVM Lambdas,
  their least-privilege permissions, and an API Gateway HTTP API.

The deployed worker is queue-driven and its SQS event source is capped at two
concurrent Lambda invocations. AWS does not permit an event-source maximum below
two; request a higher account concurrency quota and add reserved concurrency if
stricter account-level isolation becomes necessary. Name-history work is not
connected to its low-priority queue yet.

## Target architecture

```text
Browser
  |
  v
Cloudflare DNS + Pages
  |
  | HTTPS API requests
  v
API Gateway HTTP API
  |
  v
GraalVM native API Lambda
  |                 \
  | cached reads     \ refresh request
  v                   v
DynamoDB          SQS refresh queue
                       |
                       v
                GraalVM native sync Lambda
                (restricted concurrency)
                       |
                       v
                 HenrikDev API
                       |
                       v
                    DynamoDB
```

The API Lambda should perform short DynamoDB reads and enqueue refresh work. It should not wait for HenrikDev. The browser continues displaying cached data, shows a refreshing indicator, and reads updated records after the worker finishes.

### Why Cloudflare

- Cloudflare Pages can host the static Vite build without an always-running web server.
- Cloudflare provides DNS, TLS, caching, and edge protection.
- Cloudflare or API Gateway should enforce public request throttles; the Lambda-local `RateLimitFilter` is not globally authoritative.
- Keep the API origin on API Gateway. Do not put secrets or direct HenrikDev calls in the frontend.

### Why SQS instead of Redis

Redis is not required for this workload. SQS provides durable refresh jobs and Lambda concurrency control, while DynamoDB conditional writes can deduplicate refreshes. This avoids an always-running cache and VPC networking.

Use three separate controls:

1. Cloudflare/API Gateway throttling for public HTTP traffic.
2. A DynamoDB conditional lock such as `REFRESH#<puuid>` to prevent duplicate player refreshes.
3. SQS plus restricted sync-Lambda concurrency to protect the shared HenrikDev API key.

Name-history work belongs in a low-priority queue and should run only when interactive refresh work is idle.

## Repository layout

```text
backend/
  common/              Shared clients, models, DynamoDB and match processing
  api-lambda/          HTTP controllers and cached profile API
  match-sync-lambda/   Future SQS-driven synchronization worker
  infrastructure/      AWS CDK app, stacks and infrastructure tests
  pom.xml              Maven reactor build
frontend/
  src/                 React application
```

## Local development

### Requirements

- JDK 21
- Maven, or the included Maven wrapper
- Node.js 22 or newer (required by the AWS CDK CLI)
- AWS CDK CLI 2.x for infrastructure synthesis and deployment
- AWS credentials with access to a development DynamoDB table
- A HenrikDev API key

Use a separate AWS account or a clearly named development table. The current default table name is `valstats`, so verify configuration before running code that writes matches.

### Environment

PowerShell:

```powershell
$env:HDEV_KEY = "your-henrik-key"
$env:AWS_REGION = "us-east-1"
$env:AWS_PROFILE = "your-development-profile"
```

Backend configuration defaults are in `common/src/main/resources/application.yml`. Secrets must come from environment variables locally and AWS Secrets Manager or encrypted Lambda environment variables in production.

### Run backend tests

From `backend`:

```powershell
.\mvnw.bat test
```

### Run the API locally

From `backend`:

```powershell
.\mvnw.bat -pl api-lambda -am mn:run
```

The API is available at:

```text
http://localhost:8080/api/valorant
```

Health check:

```text
http://localhost:8080/health
```

### Run the frontend separately

From `frontend`:

```powershell
Copy-Item .env.example .env.local
```

Set:

```dotenv
VITE_API_BASE_URL=http://localhost:8080/api/valorant
```

Then run:

```powershell
npm ci
npm run dev
```

The frontend and backend now have independent development processes, so either can be restarted without restarting the other.

## Production configuration

At minimum, production needs:

| Setting | Purpose |
|---|---|
| `HDEV_KEY` | HenrikDev API authentication |
| `AWS_REGION` | DynamoDB/SQS region, currently `us-east-1` |
| `DYNAMODB_TABLE_NAME` | Environment-specific table name after configuration is externalized |
| `REFRESH_QUEUE_URL` | Normal refresh queue |
| `NAME_HISTORY_QUEUE_URL` | Low-priority name-history queue |
| `VITE_API_BASE_URL` | Public API Gateway URL used during the Cloudflare Pages build |

Add the Cloudflare production domain to Micronaut CORS. Do not leave production CORS restricted to localhost.

## GraalVM native Lambda plan

GraalVM native images are the intended production format for fast cold starts.

Required work:

1. Change both deployable modules from the Netty runtime to the Micronaut Lambda/custom runtime.
2. Remove `micronaut-http-server-netty` and other unused runtime dependencies from Lambda artifacts.
3. Enable Micronaut AOT and GraalVM Native Build Tools.
4. Replace `LocalRunner` with an idempotent SQS event handler.
5. Build on Linux for the same architecture used by Lambda (`arm64` or `x86_64`).
6. Package the executable as the custom-runtime `bootstrap` artifact.
7. Load-test native and JVM/SnapStart variants before selecting memory. Native is expected to start faster, but measured duration and cost should decide.

GraalVM custom runtimes do not use Lambda SnapStart. SnapStart is an alternative for a managed Java runtime, not an additional optimization to combine with the native executable.

## Cost model

Costs vary by region, payload size, Lambda memory, execution duration, log volume, and how many external pages must be synchronized. The examples below use `us-east-1` public prices available in August 2026 and intentionally round upward. Free-tier credits are not included in the estimate.

### Unit assumptions

| Service | Planning assumption |
|---|---:|
| Cloudflare Pages static hosting | Usually $0 at the initial scale if Pages Functions are not used |
| API Gateway HTTP API | About $1 per million requests at the first tier |
| Lambda requests | About $0.20 per million invocations, plus execution duration |
| DynamoDB on-demand reads | About $0.125 per million eventually consistent 4 KB read units |
| DynamoDB on-demand writes | About $0.625 per million 1 KB write units |
| SQS | First one million requests per month are currently free; request charges apply afterward |

Source pricing changes over time. Confirm estimates with the AWS Pricing Calculator before launch:

- https://aws.amazon.com/api-gateway/pricing/
- https://aws.amazon.com/lambda/pricing/
- https://aws.amazon.com/dynamodb/pricing/
- https://aws.amazon.com/sqs/pricing/
- https://developers.cloudflare.com/pages/platform/limits/

### What is an account load?

For planning, one cached account load is assumed to make approximately six API calls and consume approximately twelve DynamoDB read units. Actual browser behavior and item sizes must be measured before treating these figures as billing forecasts.

| Monthly cached account loads | API requests | DynamoDB read units | Approximate core request cost* |
|---:|---:|---:|---:|
| 1,000 | 6,000 | 12,000 | Less than $0.10 |
| 10,000 | 60,000 | 120,000 | Roughly $0.10-$0.50 |
| 100,000 | 600,000 | 1,200,000 | Roughly $1-$3 |
| 1,000,000 | 6,000,000 | 12,000,000 | Roughly $10-$30 |

\*Includes approximate API Gateway, Lambda request/duration, and DynamoDB read charges. It excludes data transfer, CloudWatch logs, storage, refresh workers, custom domains, WAF, and taxes.

### Refresh cost

A cached load is cheap. A refresh is more variable because it can call several HenrikDev pages, wait for rate limits, fetch MMR data, and write many match and aggregate records.

Use these metrics for a real forecast:

```text
refresh cost =
  sync Lambda GB-seconds
  + sync Lambda requests
  + SQS requests
  + DynamoDB writes
  + CloudWatch log ingestion
```

An incremental refresh that finds zero or a few new matches should cost a small fraction of a cent. An initial profile backfill can cost several times more. The largest avoidable cost is keeping a Lambda running while it sleeps for rate-limit windows, which is why refresh work must be queued, bounded, and checkpointed.

Add CloudWatch Embedded Metric Format metrics for:

- `CachedAccountLoads`
- `RefreshJobsEnqueued`
- `RefreshJobsDeduplicated`
- `HenrikRequests`
- `Henrik429Responses`
- `MatchesWritten`
- `RefreshDurationMs`
- `NameHistoryJobs`

These measurements allow cost per cached load and cost per refresh to be calculated from real traffic.

## AWS CDK infrastructure

Infrastructure is defined in Java in the `infrastructure` Maven module. CDK was
chosen because this workload is AWS-only and the backend already uses Java and
Maven. CDK synthesizes CloudFormation, so infrastructure changes remain
reproducible and reviewable without adding Terraform and HCL to this repository.

The implementation contains two stacks:

- `ValStats-<environment>-Stateful` contains the protected, on-demand DynamoDB
  table and its `GSI1` index. It has termination protection, deletion protection,
  point-in-time recovery, a retain removal policy, and the Henrik API secret.
- `ValStats-<environment>-Application` contains the API and sync Lambdas, HTTP
  API Gateway, refresh and name-history queues, dead-letter queues, log groups,
  queue alarms, IAM grants, and the refresh-queue event source.

The API and worker currently use the managed Java 21 Lambda runtime. GraalVM
native packaging remains a later optimization and should be selected only after
measuring the JVM deployment.

Cloudflare Pages can initially use its Git integration. Keep Cloudflare outside
CDK unless managing DNS and Pages configuration as code provides enough value to
justify an additional provider or deployment tool.

### CDK commands

Install a compatible CDK CLI, then bootstrap each AWS account and region once:

```powershell
npm install --global aws-cdk
cd infrastructure
cdk bootstrap aws://<account-id>/us-east-1
```

Synthesize and review the development environment from `infrastructure/`:

```powershell
cd ..
.\mvnw.bat package -DskipTests
cd infrastructure
cdk synth -c environment=dev -c region=us-east-1
cdk diff -c environment=dev -c region=us-east-1
```

Deploy the updated stateful stack first, then store the Henrik key without
putting its value in source control or CDK context:

```powershell
cdk deploy ValStats-dev-Stateful -c environment=dev -c region=us-east-1
$env:HDEV_KEY = "your-henrik-key"
aws secretsmanager put-secret-value `
  --secret-id valstats/dev/henrik-api-key `
  --secret-string $env:HDEV_KEY `
  --region us-east-1
Remove-Item Env:HDEV_KEY
```

Then deploy the application stack:

```powershell
cdk deploy ValStats-dev-Application -c environment=dev -c region=us-east-1
```

Use distinct AWS accounts where possible. At minimum, use distinct CDK context
values and stack names for development and production. Never commit credentials,
CDK output, API keys, or generated native binaries.

Use separate Terraform state and AWS accounts for development and production. Never place Terraform state, API keys, or generated native binaries in Git.

## GitHub Actions recommendation

Use separate validation and deployment workflows:

### Pull requests

1. Build and test the Maven reactor on JDK 21.
2. Run frontend type checking/build.
3. Run the infrastructure unit tests and `cdk synth`.
4. Produce a CloudFormation change set or `cdk diff` for review, without deploying it.

### Main branch

1. Repeat all tests.
2. Build GraalVM native artifacts on Linux.
3. Upload versioned artifacts.
4. Run `cdk deploy` only from a protected GitHub environment.
5. Deploy the Cloudflare Pages frontend after the API URL is known.
6. Run health and cached-read smoke tests.

Authenticate GitHub Actions to AWS using OpenID Connect. Do not store long-lived AWS access keys in repository secrets. Require manual approval for production applies and keep Terraform plans attached to the workflow run.

## Recommended implementation order

1. Convert match refresh into an SQS job and return HTTP `202 Accepted` immediately.
2. Add DynamoDB conditional refresh deduplication.
3. Replace the hard-coded sync runner with the SQS handler and dead-letter behavior.
4. Remove the legacy DynamoDB scan fallback after migrating old rows.
5. Convert both Lambda modules to GraalVM native custom runtimes.
6. Finish the CDK application stack for a development environment.
7. Add pull-request CI and AWS OIDC deployment workflows.
8. Deploy development, collect metrics, and revise the cost model.
9. Add the production CDK deployment and Cloudflare domain.

## API routes

All Valorant routes are below `/api/valorant`.

- `GET /matches/{region}/{name}/{tag}`
- `POST /matches/{region}/{name}/{tag}/refresh`
- `GET /matches/{region}/{name}/{tag}/refresh-status`
- `GET /modes/{region}/{name}/{tag}`
- `GET /account/{name}/{tag}`
- `GET /match/{matchid}`
- `GET /players/{puuid}`
- `GET /players/{puuid}/names`
- `GET /stats/{region}/{name}/{tag}`
- `GET /acts/{region}/{name}/{tag}`
- `GET /mmr/{region}/{name}/{tag}`

## Security and cost guardrails

- Never expose `HDEV_KEY` to the browser.
- Set AWS Budgets alerts before public launch.
- Set finite CloudWatch log retention.
- Configure SQS dead-letter queues and alarms for queue age.
- Restrict sync concurrency according to HenrikDev's rate-limit policy.
- Treat Lambda-local maps, locks and queues as caches only, never global coordination.
- Avoid a NAT Gateway unless the architecture genuinely requires private subnets; its hourly charge can exceed the rest of a small serverless stack.
- Use DynamoDB on-demand initially and revisit provisioned capacity only after traffic becomes predictable.
