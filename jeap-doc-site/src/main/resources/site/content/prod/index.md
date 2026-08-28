---
title: Documentation
slug: /
---

# Documentation

A fixture page, so that `npm start` serves something while the template is worked on. The site generator of the
jEAP Doc Service writes this directory; nothing here is packaged into the jar.

```plantuml
@startuml
component "jeap-doc-service" as doc
database "S3" as s3
doc --> s3 : publishes the site
@enduml
```

```dot
digraph {
  rankdir=LR;
  upload -> build -> publish -> serve;
}
```
