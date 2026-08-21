# jEAP Doc Service

The jEAP Doc Service receives the documentation of systems, components and libraries, stores it on S3, generates
the documentation site and serves it to the browser.

It is the central piece of the *hybrid documentation* approach: teams write their documentation as Markdown next
to their code, their build pipelines upload it, and the doc service combines it with the architecture model of the
[jEAP Arch Repo Service](https://github.com/jeap-admin-ch/jeap-archrepo-service) into one generated site.

## Documentation

| Topic                                      | Contents                                                                            |
| ------------------------------------------ | ----------------------------------------------------------------------------------- |
| [Getting started](docs/getting-started.md) | Creating a doc service instance and running it locally                              |
| [Architecture](docs/architecture.md)       | The ports-and-adapters layout, the modules and the rules they follow                |
| [Configuration](docs/configuration.md)     | The configuration properties, with their defaults                                   |
| [API](docs/api.md)                         | The REST API and its OpenAPI description                                            |
| [Security](docs/security.md)               | The semantic roles and the rule that a system may only change its own documentation |

## License

This repository is Open Source Software licensed under the [Apache License 2.0](./LICENSE).
