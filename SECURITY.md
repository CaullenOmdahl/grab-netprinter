# Security policy

## Supported versions

Only the latest release is supported with security fixes. Please upgrade before reporting.

## Reporting a vulnerability

Please report security issues privately using GitHub's
[private vulnerability reporting](https://github.com/CaullenOmdahl/grab-netprinter/security/advisories/new)
(Security → Advisories → New draft security advisory). Do not open a public issue for a
vulnerability.

Include: affected version, Android/root/LSPosed versions, the target app, reproduction steps and the
impact. You will get a response as soon as practical; please allow time for a fix before disclosure.

## Scope notes

GrabNetPrinter is a rooted, in-process instrumentation of a third-party app. The following are
**not** considered vulnerabilities of this project:

- the fact that a rooted module can read or write files with root — that is inherent to root;
- the target app detecting that it is hooked (detection resistance is out of scope);
- limitations documented in the README (BLE-GATT printing, NDK print pipelines, IPP/LPD printers).

Security-relevant reports include: hooks affecting processes outside the configured target scope,
unintended network connections, config data leaking beyond the target app, or a crash/instability
caused by the module in a hooked app.
