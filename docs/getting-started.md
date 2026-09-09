# Getting started

The jEAP Doc Service is a service template: a project creates its own doc service instance by taking
`jeap-doc-service-instance` into its POM and adding its configuration.

## Creating an instance

There are two ways to take the template. Either one contributes the whole service; they differ in how much of the
build comes along with it.

### As the parent

For a project whose only module is the instance, `jeap-doc-service-instance` is the **parent**. One version then
names the doc service - the parent version - and with it come the service, the dependency management of the
template and the jEAP parent that version of the template was built against:

```xml
<parent>
    <groupId>ch.admin.bit.jeap</groupId>
    <artifactId>jeap-doc-service-instance</artifactId>
    <version>1.5.0</version>
    <relativePath/> <!-- lookup parent from repository -->
</parent>
```

Two settings of the template's own build are worth undoing in an instance:

| | |
| --- | --- |
| **Javadoc** | The template generates a javadoc artifact because Maven Central requires one. An instance that is configuration has no API to document, so it sets `maven.javadoc.skip` back to `true` |
| **`unpack-site-manifest`** | The template manages an execution of that id for its own integration tests, where it is skipped along with the tests. An instance unpacking the site manifest for [its image](site-image.md) declares the same id and inherits that skip - and the image cannot be built without the two files, so it pins `<skip>false</skip>` |

### As a dependency

A project that already has a parent - the instance is one module of a larger reactor, or the project inherits a
parent of its own - depends on the instance POM instead:

```xml
<dependency>
    <groupId>ch.admin.bit.jeap</groupId>
    <artifactId>jeap-doc-service-instance</artifactId>
    <version>${jeap-doc-service.version}</version>
    <type>pom</type>
</dependency>
```

This form contributes the service and nothing else: the jEAP parent, the plugin settings and the dependency
management of the template stay the project's own business.

### The application class

The template ships the Spring Boot application, so an instance holds no Java at all - it names that class as the
main class of the executable jar:

```xml
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <configuration>
        <mainClass>ch.admin.bit.jeap.doc.web.DocServiceApplication</mainClass>
    </configuration>
</plugin>
```

An instance that has beans of its own to add declares its own `@SpringBootApplication` in place of it:

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
    build:
      node-command: /opt/node/bin/node
      node-modules-directory: /opt/jeap-doc/node_modules
    publication:
      url: https://doc.example.ch
```

See [Configuration](configuration.md) for the properties and [Security](security.md) for the roles a client needs.

## An instance needs a Node runtime

The doc service generates the documentation site by running the site generator as a child process, so **the
instance's image has to carry a Node runtime and the site template's installed dependencies**, and the service
does not start without them. [The site image](site-image.md) is the Dockerfile to copy.

On a developer machine that means Node 24 on the `PATH` (`nvm`, `asdf` or the distribution's package) and
`node-modules-directory` pointing at an installed copy of the template's dependencies.

Building the repository additionally needs **Chrome**: the site template is a React application, and the tests
that drive it in a browser take the one installed on the machine rather than downloading one. A missing browser
fails the build - a browser suite that skips itself would be green because it ran nothing.

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
- [Generating the documentation](generation.md)
- [The site image](site-image.md)
