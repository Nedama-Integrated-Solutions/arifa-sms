# Changelog

## 0.9.0

First public release.

- Sends alarm notifications as SMS through Africa's Talking.
- Acknowledge an alarm by replying to the SMS with the code it contains.
- Retries once after a temporary failure, and logs the reason.
- Logs the cost of every send, and warns about an unexpected currency.
- Caps SMS per hour so a runaway alarm cannot empty the balance.
- Supports Ignition 8.1.33 and later (tested on 8.1.53).
