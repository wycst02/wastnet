# Security Policy

## Reporting a Vulnerability

wastnet takes security seriously. If you discover a security vulnerability, we encourage you to report it privately via email so we can address it before public disclosure. If the issue does not involve security risks, you may also submit it directly via GitHub Issues.

- Email: [shallxiao@126.com](mailto:shallxiao@126.com)

We will acknowledge receipt within **48 hours** and follow up promptly.

## Disclosure Process

1. Submit vulnerability details (reproduction steps, impact scope, suggested fix)
2. We confirm and evaluate the severity
3. Fix in progress...
4. Release a patched version and credit the reporter (with your consent)

## Supported Versions

| Version | Supported |
| --- | --- |
| Latest 1.x | ✅ Actively maintained |
| 1.0.2 | ✅ Current stable release |

## Security Best Practices

- Enable SSL/TLS encryption in production
- Use `ConnectionFilter` to restrict source IPs
- Configure HTTP request size limits (`HttpConf`) based on your scenario
- Avoid enabling Debug log level on public-facing services
