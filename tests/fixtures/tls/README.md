# Local TLS test fixtures

These certificates and the **publicly known test server private key** are only
for loopback regression tests. Never use them in a deployed service or add this
CA to an operating system trust store. The CA private key is not retained.

The leaf certificate covers `localhost` only, so connecting to `127.0.0.1` tests
hostname rejection. Certificates expire in September 2036. These fixtures are
not included in desktop distributions.
