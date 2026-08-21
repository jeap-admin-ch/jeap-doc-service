# API

The doc service's REST API lives under `/api` and is authenticated with a bearer token.

The API is described with OpenAPI. The jEAP Swagger starter serves the description at `/api-docs` and the Swagger
UI at `/swagger-ui.html`; both are switched off unless the instance sets `jeap.swagger.status` (see
[Configuration](configuration.md)).

## Uploading a documentation set

```
PUT /api/docs/uploads/{uploadId}?system={system}
Content-Type: application/zip
Authorization: Bearer ...
```

| Parameter    | Required | Description                                                                                     |
| ------------ | -------- | ----------------------------------------------------------------------------------------------- |
| `{uploadId}` | yes      | A UUID **the client chooses** before the request, so an upload can be retried under the same id |
| `system`     | yes      | The system the documentation belongs to. The write role must be granted for exactly this system |
| body         | yes      | The ZIP archive of the documentation set                                                        |

```bash
curl -X PUT "https://docs.example.ch/api/docs/uploads/$(uuidgen)?system=wvs" \
     -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/zip" \
     --data-binary @docs.zip
```

| Status | Meaning                                                      |
| ------ | ------------------------------------------------------------ |
| `200`  | The upload was accepted                                      |
| `400`  | A parameter is missing or malformed                          |
| `401`  | No or invalid token                                          |
| `403`  | The token does not grant the write role for the given system |
| `415`  | The body is not a ZIP archive                                |

## Related

- [Architecture](architecture.md)
- [Security](security.md)
