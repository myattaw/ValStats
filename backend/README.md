## Micronaut 4.9.4 Documentation

- [User Guide](https://docs.micronaut.io/4.9.4/guide/index.html)
- [API Reference](https://docs.micronaut.io/4.9.4/api/index.html)
- [Configuration Reference](https://docs.micronaut.io/4.9.4/guide/configurationreference.html)
- [Micronaut Guides](https://guides.micronaut.io/index.html)
---

## Handler

Handler: io.micronaut.function.aws.proxy.payload1.ApiGatewayProxyRequestEventFunction

[AWS Lambda Handler](https://docs.aws.amazon.com/lambda/latest/dg/java-handler.html)

- [Micronaut Maven Plugin documentation](https://micronaut-projects.github.io/micronaut-maven-plugin/latest/)

## Valorant API Integration

This application integrates with the Valorant API to fetch match data for players.

### Development Approach

This project uses a dual-mode architecture:

1. **Local Development Mode** (Current Focus)
   - Run the application as a standard Micronaut application
   - Use the REST endpoints directly
   - Faster development cycle with hot reloading

2. **AWS Lambda Mode** (Future Deployment)
   - The Lambda handler code is included but can be ignored during local development
   - Will be used when ready to deploy to AWS

### Endpoints

- `/api/valorant/hello` - Hello World endpoint that fetches matches for "yoru smurf#rages" in NA region
- `/api/valorant/matches/{region}/{name}/{tag}` - Fetch matches for a specific player

### Running Locally

```bash
./mvnw mn:run
```

Then access: http://localhost:8080/api/valorant/hello

### Lambda Deployment (For Future Use)

When ready to deploy to AWS Lambda:

```bash
./mvnw package
```

Upload the resulting JAR file to AWS Lambda.

## Feature aws-lambda-events-serde documentation

- [Micronaut AWS Lambda Events Serde documentation](https://micronaut-projects.github.io/micronaut-aws/snapshot/guide/#eventsLambdaSerde)

- [https://github.com/aws/aws-lambda-java-libs/tree/main/aws-lambda-java-events](https://github.com/aws/aws-lambda-java-libs/tree/main/aws-lambda-java-events)


## Feature serialization-jackson documentation

- [Micronaut Serialization Jackson Core documentation](https://micronaut-projects.github.io/micronaut-serialization/latest/guide/)


## Feature snapstart documentation

- [https://docs.aws.amazon.com/lambda/latest/dg/snapstart.html](https://docs.aws.amazon.com/lambda/latest/dg/snapstart.html)


## Feature aws-lambda documentation

- [Micronaut AWS Lambda Function documentation](https://micronaut-projects.github.io/micronaut-aws/latest/guide/index.html#lambda)


## Feature micronaut-aot documentation

- [Micronaut AOT documentation](https://micronaut-projects.github.io/micronaut-aot/latest/guide/)


## Feature http-client-jdk documentation

- [Micronaut HTTP Client Jdk documentation](https://docs.micronaut.io/latest/guide/index.html#jdkHttpClient)

- [https://openjdk.org/groups/net/httpclient/intro.html](https://openjdk.org/groups/net/httpclient/intro.html)


## Feature maven-enforcer-plugin documentation

- [https://maven.apache.org/enforcer/maven-enforcer-plugin/](https://maven.apache.org/enforcer/maven-enforcer-plugin/)


## Feature aws-lambda-custom-runtime documentation

- [Micronaut Custom AWS Lambda runtime documentation](https://micronaut-projects.github.io/micronaut-aws/latest/guide/index.html#lambdaCustomRuntimes)

- [https://docs.aws.amazon.com/lambda/latest/dg/runtimes-custom.html](https://docs.aws.amazon.com/lambda/latest/dg/runtimes-custom.html)
