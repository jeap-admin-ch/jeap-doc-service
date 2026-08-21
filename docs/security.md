# Security

## Roles

The doc service authorizes against **semantic roles**, which requires
`jeap.security.oauth2.resourceserver.system-name` to be set on the instance. Two roles are foreseen:

| Role                                   | Grants                                                            |
| -------------------------------------- | ----------------------------------------------------------------- |
| `<system-name>_%<system>_@docs_#write` | Changing the documentation of the system named in the tenant part |
| `<system-name>_@docs_#read`            | Reading the doc service's API                                     |

## A system may only change its own documentation

The system a documentation set belongs to is a parameter of the upload, and the write role carries the system it
is granted for in its **tenant** part, so the service authorizes every upload against exactly that system.

A pipeline holding `<system-name>_%wvs_@docs_#write` can upload documentation for the system `wvs` and receives
`403` for every other system. Granting the role without a tenant part makes it a wildcard over all systems, which
is what an administrative client would hold.

## Authentication

The REST API is an OAuth2 resource server: clients authenticate with a bearer token, in the `SYS` context for a
build pipeline. Everything outside `/api` stays with the chains of the jEAP security and Swagger starters.

## Content Security Policy

The documentation the service will serve is self-contained, so it is served with a restrictive Content Security
Policy that allows no external content at all:

```
default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:;
font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'
```

The policy, and the other security headers, are applied by the
[jEAP web config starter](https://jeap-admin-ch.github.io/docs/jeap-spring-boot-starters/jeap-spring-boot-web-config-starter),
which skips the API and the actuator paths.

## Related

- [API](api.md)
- [Configuration](configuration.md)
