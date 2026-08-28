# jEAP Doc Service

The jEAP Doc Service receives the documentation of systems, components and libraries, stores it on S3, generates
the documentation site and serves it to the browser.

It is the central piece of the *hybrid documentation* approach: teams write their documentation as Markdown next
to their code, their build pipelines upload it, and the doc service combines it with the architecture model of the
[jEAP Arch Repo Service](https://github.com/jeap-admin-ch/jeap-archrepo-service) into one generated site.

## Documentation

| Topic                                                | Contents                                                                               |
|------------------------------------------------------|----------------------------------------------------------------------------------------|
| [Getting started](docs/getting-started.md)           | Creating a doc service instance and running it locally                                 |
| [Architecture](docs/architecture.md)                 | The ports-and-adapters layout, the modules and the rules they follow                   |
| [Configuration](docs/configuration.md)               | The configuration properties, with their defaults                                      |
| [API](docs/api.md)                                   | The REST API and its OpenAPI description                                               |
| [Uploads](docs/uploads.md)                           | What happens to an uploaded bundle, the state of an upload, and what a retry does      |
| [Security](docs/security.md)                         | The semantic roles and the rule that a system may only change its own documentation    |
| [Generation](docs/generation.md)                     | How the documentation site is generated, what triggers a build and how it is published |
| [The site image](docs/site-image.md)                 | How to build a doc service image that can run the site generator                       |
| [Observability](docs/observability.md)               | The meters of the uploads and the builds, and what to alarm on                         |
| [Operating the bucket](docs/operating-the-bucket.md) | What the bucket has to expire, and what it must never expire                           |

## Building it

`./mvnw verify` needs **Docker** for the integration tests, **Node 24** for the documentation site the service
generates, and **Chrome** for the browser tests that drive that site - all three are preconditions of the build
rather than something it works around. See [Getting started](docs/getting-started.md).

## License

This repository is Open Source Software licensed under the [Apache License 2.0](./LICENSE).
