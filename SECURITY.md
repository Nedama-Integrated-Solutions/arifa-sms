# Security

This describes how Arifa is built to be safe, what it assumes, and where its limits are. It comes from reading
the source and from the test suite, not from an outside penetration test. Where a statement rests on Inductive
Automation's documentation rather than something tested here, it says so.

## What Arifa trusts

- **The Ignition gateway.** Anyone who can edit the alarm pipelines, the profile settings or the user source
  already controls what Arifa does. Ignition's own permissions guard that, not Arifa.
- **Africa's Talking.** Arifa trusts what Africa's Talking's API returns over HTTPS, authenticated by your API key.
- **Nothing else.** Arifa opens no port and accepts no inbound connection. Replies are read by Arifa asking Africa's
  Talking, on a timer, only while a code is waiting. There is nothing on the plant network for an outside
  attacker to connect to.

## What stops a stranger acknowledging an alarm

A reply acknowledges an alarm only when **all** of these hold:

1. It parses as a reply: the keyword or `ACK`, then exactly four digits.
2. The code matches one that is currently waiting.
3. The reply came from a phone number the matching alarm SMS was actually sent to.
4. The reply was sent after that SMS (allowing for a small clock difference).
5. The code has not already been used. A code works once and expires, 60 minutes by default.

Guessing a code is not useful on its own, because step 3 must also hold: the sender would already have to be one of
the numbers the alarm went to. The test suite covers a wrong code, a right code from the wrong number, a reply sent
before the alarm, and reuse of a spent code.

A registered recipient is not rate-limited on wrong guesses. They are already a person the system trusts, and each
guess is an SMS they pay for.

## The API key

**On Ignition 8.1** the key is stored in an `EncodedStringField`, which Ignition encodes with its `GatewaySec` class. Ignition's
8.1 SDK documentation for that class says it **uses a default encryption key unless the gateway administrator sets
a custom one** with the `-DencodingKey` startup option. This was not tested by decrypting a key from a real gateway
database, so it rests on that documentation.

In practice: on a gateway with Ignition's default settings, someone who obtains a copy of the gateway's internal
database, for example from a backup, may be able to recover the Africa's Talking key. That is a property of Ignition's
secret storage in general, not of Arifa, and Arifa cannot change it.

**On Ignition 8.3** the key is stored as an *embedded secret*. Ignition's Secrets Management encrypts it when the
profile is saved, and keeps it out of the shared gateway configuration. The settings screen also offers *Referenced*,
to take the key from a secret provider. Inductive Automation's own tutorial notes that, in its example, a secret was
encrypted with the default encryption key, so the advice below still applies: follow Inductive Automation's Secrets
Management documentation to harden your gateway's encryption, and protect gateway backups.

**Recommended:** on 8.1, set a custom encoding key on the gateway. On both, protect gateway backups as you would a file that holds a
live API key, and use a separate Africa's Talking account, and so a separate key, for each site. If a key is
exposed, generate a new one in the Africa's Talking dashboard, which replaces the old one, and update the profile.

The key never appears in Arifa's logs or error messages, and a full phone number never appears in a log line. The
test suite asserts both.

## Limits that come with SMS

- SMS is **not end-to-end encrypted**. Alarm text and acknowledgement codes cross the carrier network. Do not put
  anything in an alarm message that you would not want a carrier to see.
- Sender numbers can, in principle, be spoofed at the telecom level. This is true of SMS everywhere and cannot be
  detected from inside this module.
- Delivery to the handset is not confirmed. Arifa reports "accepted by Africa's Talking".
- Codes are held in memory only. After a gateway restart or a failover, replies to earlier messages are ignored.
  The alternative, saving codes to disk, is more state to protect.
- The hourly SMS cap protects the balance but can also stop real alarms during a flood. Size it for a bad day.

## What was checked

- **No third-party runtime libraries.** The core library depends only on the JDK. JUnit is used for tests only.
- **No inbound exposure.** Every network call in the module is an outbound HTTPS request that Arifa starts.
- **TLS is not weakened.** The HTTP client uses the JDK's default trust store and hostname verification, and does
  not follow redirects.
- **The JSON parser is bounded.** The small built-in parser limits nesting to 64 levels, so a malformed response
  cannot exhaust the stack.
- **Releases are signed.** Ignition checks the signature when you install, and the certificate thumbprint is published in the README.
- **Secrets are scanned for.** The repository carries a pre-commit hook and a rule for Africa's Talking keys,
  which the standard scanner rules do not know by name.
- Every build and test run happens on a clean machine in GitHub Actions, with the actions pinned to exact commits.

## Reporting a problem

Please do not open a public issue for a security problem. Use GitHub's private reporting: on this repository, open
the **Security** tab and choose **Report a vulnerability**. Include what you found and how to reproduce it.

Only the latest release is supported.
