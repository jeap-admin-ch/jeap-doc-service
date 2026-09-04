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
| [Architecture import](docs/architecture-import.md)   | What is replicated from the architecture repository, and what a partial run leaves     |
| [The scheduled jobs](docs/scheduled-jobs.md)         | Every job the service runs on its own, its schedule, its lock and which may overlap    |
| [Structure templates](docs/structure-templates.md)   | The template plugin point, the arc42 chapters and how to add a methodology             |
| [The site image](docs/site-image.md)                 | How to build a doc service image that can run the site generator                       |
| [Observability](docs/observability.md)               | The meters of the uploads and the builds, and what to alarm on                         |
| [Operating the bucket](docs/operating-the-bucket.md) | What the bucket has to expire, and what it must never expire                           |

## Building it

`./mvnw verify` needs **Docker** for the integration tests, **Node 24** for the documentation site the service
generates, and **Chrome** for the browser tests that drive that site - all three are preconditions of the build
rather than something it works around. See [Getting started](docs/getting-started.md).

## Structure templates

Documentation is organised by a **structure template**. It says which chapters exist and where a page is served.
The doc service ships [arc42](https://arc42.org) as `jeap-doc-template-arc42`. It generates the system
documentation from the architecture model into the arc42 sections, and the documentation a team writes is filed
into the same ones. A further methodology is a further module, and nothing outside it names a template.

## License

This repository is Open Source Software licensed under the [Apache License 2.0](./LICENSE).

The arc42 template is by Gernot Starke and Peter Hruschka and is licensed under
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/); see [NOTICE](./NOTICE) for the attribution and
for what this service changed.
