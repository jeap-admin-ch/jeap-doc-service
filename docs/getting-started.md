# Getting started

The jEAP Doc Service is a service template: a project creates its own doc service instance by depending on
`jeap-doc-service-instance` and adding its configuration.

## Creating an instance

```xml
<dependency>
    <groupId>ch.admin.bit.jeap</groupId>
    <artifactId>jeap-doc-service-instance</artifactId>
    <version>${jeap-doc-service.version}</version>
    <type>pom</type>
</dependency>
```

The instance provides its own Spring Boot application class:

```java
@SpringBootApplication
public class MyDocServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyDocServiceApplication.class, args);
    }
}
```

Everything else - the REST API, its security, the object storage and the persistence - is contributed by the
auto-configurations of the template's modules.

## Minimal configuration

```yaml
spring:
  application:
    name: my-doc-service
  datasource:
    url: jdbc:postgresql://localhost:5432/docservice
    username: docservice
    password: ${DB_PASSWORD}

jeap:
  security:
    oauth2:
      resourceserver:
        system-name: mydoc           # activates the semantic role model
        authorization-server:
          issuer: https://keycloak.example.ch/auth/realms/system
  s3:
    client:
      region: eu-central-1
  doc:
    storage:
      bucket: my-doc-service-documents
```

See [Configuration](configuration.md) for the properties and [Security](security.md) for the roles a client needs.

## Running it locally

The service needs a PostgreSQL database and an S3-compatible object storage; the bucket has to exist, otherwise
the service refuses to start. For local development, run [RustFS](https://rustfs.com) as the object storage and
point the S3 client at it:

```yaml
jeap:
  s3:
    client:
      endpoint-url: localhost:9000
      access-key: dev
      secret-key: devsecret
      tls: false
```

## Building this repository

```bash
./mvnw verify
```

The integration tests use Testcontainers and therefore need a running Docker daemon: they start a PostgreSQL and a
RustFS container and run the service against them.

## Related

- [Architecture](architecture.md)
- [API](api.md)
- [Configuration](configuration.md)
