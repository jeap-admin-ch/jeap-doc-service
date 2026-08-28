# Building an image with the site generator

The doc service generates the documentation site by running the site generator as a child process, so its image
needs a Node runtime and the dependencies of the site template beside the application. This page is the example
to copy.

## What has to be in the image, and what does not

The site template is two things with two different lifetimes:

|                                                                                          | Where it comes from                                                                                                                                                                              |
|------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **The template sources** - the generator's configuration, its components and its plugins | The `jeap-doc-site` jar, on the service's own classpath. They are extracted into the build workspace on every build, so the template is by definition the version this service was built against |
| **`node_modules`**                                                                       | **The image.** Tens of thousands of files produced by a tool, which cannot be packaged into a jar and must not be fetched at run time                                                            |

So the image carries the Node runtime, `node_modules` and the `package-lock.json` they were installed from - and
**no template sources at all**. It also needs no npm registry at run time: nothing is installed while the service
runs.

## The Maven build of the instance

`npm ci` reads two files, and they come out of the ordinary `jeap-doc-site` artifact:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-dependency-plugin</artifactId>
    <executions>
        <execution>
            <id>unpack-site-manifest</id>
            <phase>prepare-package</phase>
            <goals><goal>unpack</goal></goals>
            <configuration>
                <artifactItems>
                    <artifactItem>
                        <groupId>ch.admin.bit.jeap</groupId>
                        <artifactId>jeap-doc-site</artifactId>
                        <includes>site/package.json,site/package-lock.json</includes>
                        <outputDirectory>${project.build.directory}/site</outputDirectory>
                        <fileMappers>
                            <org.codehaus.plexus.components.io.filemappers.FlattenFileMapper/>
                        </fileMappers>
                    </artifactItem>
                </artifactItems>
            </configuration>
        </execution>
    </executions>
</plugin>
```

An instance whose `.dockerignore` allows only what it needs has to allow these too:

```text
!target/site/package.json
!target/site/package-lock.json
```

## The Dockerfile

```dockerfile
# ---------------------------------------------------------------------------
# 1. Install node_modules. Only package.json and package-lock.json are needed:
#    the template sources live in the jeap-doc-site jar and are extracted into
#    the build workspace at run time.
# ---------------------------------------------------------------------------
FROM public.ecr.aws/docker/library/node:24-bookworm-slim AS site

# The official image ships a non-root "node" user; /home/node is its own.
USER node
WORKDIR /home/node/build

COPY --chown=node:node target/site/package.json target/site/package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund

# ---------------------------------------------------------------------------
# 2. The service, on the jEAP runtime image. That image creates the user
#    "appuser", owns /app to it and ends with USER appuser - so everything
#    below runs unprivileged and nothing here switches back to root.
# ---------------------------------------------------------------------------
FROM <the jEAP Corretto runtime image>

# The Node runtime. Just the binary: the build is started as
# `node .../docusaurus.mjs`, so npm is not in the runtime image at all.
COPY --from=site /usr/local/bin/node /opt/node/bin/node

# What the build resolves its imports against, and the lockfile they were
# installed from - the doc service compares that one against the copy in its
# own jar at startup and refuses to start when they differ.
COPY --from=site /home/node/build/node_modules      /opt/jeap-doc/node_modules
COPY --from=site /home/node/build/package-lock.json /opt/jeap-doc/package-lock.json

# No mkdir and no chown: the workspace lives under /app, which the base image
# already owns to appuser, and the service creates it while it starts.
COPY --chown=appuser:appuser target/my-doc-service.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
```

And the configuration that belongs with it:

```yaml
jeap:
  doc:
    build:
      node-command: /opt/node/bin/node
      node-modules-directory: /opt/jeap-doc/node_modules
      workspace-directory: /app/build      # under /app, which appuser owns
```

## Five things worth knowing

- **Nothing runs as root.** The runtime image switches to a non-root user and this Dockerfile never switches
  back: the workspace goes under a directory that user already owns, and the service creates it while it starts.
  `COPY` writes as root regardless, which is right for `node_modules` and the Node binary - **the service cannot
  modify its own toolchain**, and read-only is the correct permission for it.
- **npm is not in the runtime image.** The build is started as `node …/@docusaurus/core/bin/docusaurus.mjs`,
  which removes a symlink into a root-owned directory, a `PATH` question and a process layer at once.
- **Check that the copied Node runs.** The builder is Debian and the runtime image is Amazon Linux;
  `docker run <image> /opt/node/bin/node --version` is the whole test, and it belongs in the image build. Should
  it fail, install Node from the distribution in the runtime stage instead and drop the two `COPY --from` lines.
- **Pin the Node image by digest**, as the runtime image is pinned. A floating tag would let the Node version
  under a reproducible `package-lock.json` change without anything recording it.
- **Rebuild the image when the doc service is bumped.** The service compares the lockfile in its jar against the
  one the image installed from and does not start when they differ - which is exactly what bumping the dependency
  without rebuilding produces.

## What the build needs from the container

A documentation build is a Node process, and it is the largest thing the doc service does.

|        |                                                                                                                                                                                                                                                         |
|--------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Memory | Give the container room for the JVM **and** for the build. Cap the build with `jeap.doc.build.max-node-memory` (1 GB by default) and leave the JVM less than the whole container - on AWS that means lowering `MaxRAMPercentage` rather than raising it |
| CPU    | A build is mostly single-threaded but wants a core while it runs. One build runs per instance at a time                                                                                                                                                 |
| Disk   | The workspace holds the template, the content and the output. A few hundred MB is generous for a small site; `documentation_build.size_in_bytes` says the real number after the first builds                                                            |

## Related

- [Generating the documentation](generation.md) - what a build does and what triggers one
- [Configuration](configuration.md) - the properties named here
- [Architecture](architecture.md) - where the site generator sits
