# Arifa profile settings

Every setting on the Arifa SMS notification profile (Config > Alarming > Notification). Blank means the default
shown here.

## Africa's Talking Account

| Setting | Default | What it does |
| --- | --- | --- |
| Username | none | The Africa's Talking app **username**. Not the app's display name. Only used when Live is on |
| API Key | none | The app's key, stored encoded in the gateway. Type it twice. A gateway backup contains it |
| Live | off | Off sends to Africa's Talking's free simulator, with the username `sandbox` and a sandbox key. On sends real SMS, billed to the account |
| Sender ID | blank | The registered sender name, or the shortcode when replies are used. Blank uses Africa's Talking's default sender |

On **Ignition 8.1** the **API Key** appears as **two boxes, both labelled Password**. That is Ignition's standard
password editor. Type the key in the first box and the same key again in the second.

On **Ignition 8.3** the **API Key** is a secret field with three choices. Choose **Embedded**, then type the key.
Choose **Referenced** only to take the key from a secret provider. Leave it on **None** and the profile cannot send.

## Delivery

| Setting | Default | What it does |
| --- | --- | --- |
| Default Country Code | 254 | Digits only. Added to numbers typed as `07...`. Kenyan numbers are checked to be mobile numbers |
| Request Timeout (seconds) | 10 | How long to wait for Africa's Talking before giving up. One retry follows a temporary failure |
| Maximum SMS per Hour | 100 | A cost cap over any rolling 60 minutes. One SMS part to one person counts as 1. A 2-part message to 3 people counts as 6. At the cap nothing more is sent, the profile status shows the limit, and Arifa reports the notification to Ignition as failed. **0 means no limit** |
| Expected Cost Currency | KES | If Africa's Talking reports a cost in another currency, Arifa logs `UNEXPECTED COST`. On Airtel Kenya that can mean a message was billed as international |

The cap protects the balance. Saving the profile clears the count. A cap that is too low can stop real alarms, so
size it for a bad day.

## Two-Way Acknowledgement

| Setting | Default | What it does |
| --- | --- | --- |
| Allow Acknowledgement by SMS Reply | off | Adds `Reply NEDAMA 4821 to ack` to each alarm SMS. Needs a shortcode in Sender ID |
| Reply Keyword | NEDAMA | The word typed before the code. Letters only, up to 12. A single `-` means no keyword |
| Code Valid For (minutes) | 60 | How long a reply can still acknowledge the alarm |
| Check for Replies Every (seconds) | 10 | How often Africa's Talking is asked for replies, only while a code is waiting. At least 3 |
| Send Confirmation SMS | off | Sends the operator a short SMS saying whether the acknowledgement worked. Costs one more SMS |

Codes are kept in memory. After a gateway restart or a failover, replies to earlier messages are ignored.

## Auditing

| Setting | Default | What it does |
| --- | --- | --- |
| Audit Profile | none | If chosen, each notification is written to the audit system |

## On the pipeline's Notification block

| Property | What it does |
| --- | --- |
| Message | The alarm text, an expression. Cleaned to the GSM alphabet and cut to at most 2 SMS. The default is `{eventTime|HH:mm:ss} {name}: {eventState} ({displayPath})` |
| Throttled Message | Used when several alarms are consolidated into one SMS |
| Test Mode | Yes logs what would be sent and sends nothing. No sends for real |

## What you can read

| Where | What |
| --- | --- |
| Gateway log, search `Arifa` | Each send with its cost, retries with the reason, refused sends, replies, limits |
| Config > Alarming > Notification | Running, `Last send failed: ...`, or `SMS limit reached: ...` |
| Africa's Talking dashboard SMS log | What was delivered and what it cost. Arifa reports "accepted", not "delivered" |
