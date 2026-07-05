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
| `refresh_reused` | 401 | A refresh token that was already consumed was presented; entire token family revoked (theft detection triggered) | AUTH-03 |
| `refresh_revoked` | 401 | The refresh token has been explicitly revoked (e.g. via logout or family revocation) | AUTH-03 |
| `refresh_expired` | 401 | The refresh token exists but its 7-day expiry has passed; re-login required | AUTH-03 |
| `invalid_refresh` | 401 | The refresh token value does not match any row in the database | AUTH-03 |
| `email_taken` | 409 | Registration attempted with an email that already has an account (case-insensitive) | AUTH-04 |
| `password_too_weak` | 400 | Password scored below 3 on the zxcvbn 0–4 scale; the error object carries an optional integer extension field `password_score` with the measured score so clients can render "score N of 4" without re-running zxcvbn | AUTH-04 |
| `invalid_reset_token` | 400 | Password-reset token is unknown, already used, or expired (deliberately indistinguishable) | AUTH-05 |

Later features must append new codes to this table with their feature slug in the "Registered by" column. The reviewer enforces this.
