# API Error Codes

All error responses follow [RFC 7807](https://datatracker.ietf.org/doc/html/rfc7807) and include a project-specific `errors` array with stable machine-readable `code` values for frontend i18n.

## Error response shape

```json
{
  "type": "https://api.thedogs.app/problems/<slug>",
  "title": "...",
  "status": 4xx,
  "detail": "...",
  "errors": [{ "code": "<code>", "field": "<field>" }]
}
```

The `field` key is present only for validation errors.

## Registered codes

| Code | Status | Description | Registered by |
|------|--------|-------------|---------------|
| `unauthorized` | 401 | No token or missing Authorization header | AUTH-01 |
| `token_expired` | 401 | JWT `exp` claim is in the past | AUTH-01 |
| `invalid_token` | 401 | JWT has invalid signature, is malformed, or uses unknown issuer | AUTH-01 |
| `forbidden` | 403 | Authenticated but lacks the required role | AUTH-01 |
| `not_found` | 404 | Resource does not exist or is not visible to caller | AUTH-01 |
| `conflict` | 409 | Request conflicts with existing state (e.g., duplicate resource) | AUTH-01 |
| `field_required` | 400 | A required field is missing or blank | AUTH-01 |
| `field_too_short` | 400 | Field value is shorter than the minimum allowed length | AUTH-01 |
| `field_too_long` | 400 | Field value exceeds the maximum allowed length | AUTH-01 |
| `field_invalid_format` | 400 | Field value does not match the expected format | AUTH-01 |
| `bad_credentials` | 401 | Email address is unknown or the password is incorrect (unified to prevent user enumeration) | AUTH-02 |
| `account_disabled` | 401 | User account exists but has been disabled by an admin | AUTH-02 |
| `too_many_attempts` | 429 | Too many failed login attempts from this IP; `Retry-After` header indicates seconds to wait | AUTH-02 |

Later features must append new codes to this table with their feature slug in the "Registered by" column. The reviewer enforces this.
