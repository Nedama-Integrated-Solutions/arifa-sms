# Changelog

## 0.10.0

- New: a module for **Ignition 8.3** (8.3.3 or later, tested on 8.3.10), with the same features and settings as the 8.1
  module. Each Ignition line has its own file on the release page.
- On 8.3 the API key is stored as an Ignition secret.
- A profile saved on an 8.1 gateway is not carried over when the gateway is upgraded to 8.3. Create it again.
- The 8.1 module is unchanged.

## 0.9.0

First public release.

- Sends alarm notifications as SMS through Africa's Talking.
- Acknowledge an alarm by replying to the SMS with the code it contains.
- Retries once after a temporary failure, and logs the reason.
- Logs the cost of every send, and warns about an unexpected currency.
- Caps SMS per hour so a runaway alarm cannot empty the balance.
- Supports Ignition 8.1.33 and later (tested on 8.1.53).
