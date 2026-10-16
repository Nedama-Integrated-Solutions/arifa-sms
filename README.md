## Arifa

### SMS alarm notification and acknowledgement for Ignition by Inductive Automation

### [Nedama Integrated Solutions](https://nedama.com)

[![build](https://github.com/Nedama-Integrated-Solutions/arifa-sms/actions/workflows/build.yml/badge.svg)](https://github.com/Nedama-Integrated-Solutions/arifa-sms/actions/workflows/build.yml)
[![Ignition](https://img.shields.io/badge/Ignition-8.1%20%7C%208.3-orange)](https://inductiveautomation.com/)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

## Directory

| Module | Description | Release Notes |
| --- | --- | --- |
| [Arifa SMS Notification for Ignition 8.1](docs/install-guide.md) | Sends alarm notifications as SMS through Africa's Talking. The operator can acknowledge an alarm by replying to the SMS. | [![version](https://img.shields.io/github/v/release/Nedama-Integrated-Solutions/arifa-sms?style=for-the-badge&label=version)](CHANGELOG.md) |
| [Arifa SMS Notification for Ignition 8.3](docs/install-guide.md) | Sends alarm notifications as SMS through Africa's Talking. The operator can acknowledge an alarm by replying to the SMS. | [![version](https://img.shields.io/github/v/release/Nedama-Integrated-Solutions/arifa-sms?style=for-the-badge&label=version)](CHANGELOG.md) |

## Verify

Both modules are signed with the same certificate. Ignition shows its **Thumbprint** when you install the module. It must read:

```
65 83 84 55 22 06 7C CC F9 C6 9B C6 F3 C6 41 98 3A 28 BD 20
```

## Build

```
git clone https://github.com/Nedama-Integrated-Solutions/arifa-sms
cd arifa-sms
mvn verify
```

Needs JDK 17 and Maven 3.8 or later.

## Links

- [Downloads](../../releases)
- [Install guide](docs/install-guide.md) and [settings](docs/settings-reference.md)
- [Security policy](SECURITY.md)
- [License (Apache-2.0)](LICENSE)
- [Nedama Integrated Solutions](https://nedama.com)
- [Inductive Automation](https://inductiveautomation.com/)
- [Africa's Talking](https://africastalking.com)

Independent project. Ignition and Inductive Automation are trademarks of Inductive Automation. Africa's Talking is a trademark of its owner.
