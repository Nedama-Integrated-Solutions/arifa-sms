# Installing Arifa SMS Notification

This is for the person who looks after the Ignition gateway. There is one file for each Ignition line. Download the
one that matches your gateway, because the two are not interchangeable.

## What you need

| | |
| --- | --- |
| Ignition | **8.1.33 or later** in the 8.1 line (tested on 8.1.53), or **8.3.3 or later** in the 8.3 line (tested on 8.3.10) |
| Gateway access | An administrator login |
| From the release page | The `.modl` file for your Ignition line: `...-ignition-8.1.modl` or `...-ignition-8.3.modl` |
| For sending | An Africa's Talking app **username** and **API key**. The free sandbox needs no payment. Real SMS need a balance |
| People | Each on-call person's mobile number in their Ignition user, and their agreement to receive alarm SMS |

## 1. Back up first

Download a gateway backup from the gateway's backup page. Keep the file safe. A backup contains your stored API key.

## 2. Check the file (optional)

Ignition checks the module's signature when you install it, so a changed file is refused. To look at a downloaded
module first, on any machine with a JDK, using `tools/VerifyModule.java` from this repository:

```
java tools/VerifyModule.java Arifa-SMS-Notification-0.10.0-ignition-8.3.modl
```

It ends with `RESULT: OK` and prints the certificate thumbprint, which must match the one in the
[README](../README.md#verify).

## 3. Install the module

**Ignition 8.1**

1. Config > System > Modules > Install or Upgrade a Module, and choose the `.modl` file.
2. Ignition shows the signing certificate with the warning "This certificate is self-signed" and "This module was not
   written by Inductive Automation". That is expected: Arifa is signed by Nedama Integrated Solutions.
3. **Compare the Thumbprint** with the one in the [README](../README.md#verify). If they differ, stop.
4. Accept. **Arifa SMS Notification** should be listed as running, with no trial countdown.

**Ignition 8.3**

1. Platform > System > Modules > **Install or Upgrade Module +**, and choose the `.modl` file.
2. Ignition 8.3 may list a self-signed module under **Quarantined Modules** at the bottom of the page. If so, open
   its three-dot menu and resolve the quarantine.
3. On the installed module, open the three-dot menu and choose **View Certificate**. **Compare the Thumbprint** with
   the one in the [README](../README.md#verify). If they differ, stop.
4. Restart the gateway if the page asks. **Arifa SMS Notification** should be listed as active.

If you ever installed an unsigned test build, uninstall it, restart the Ignition service, and then install the signed one.

## 4. Create the profile

Ignition 8.1: Config > Alarming > Notification > Create new Alarm Notification Profile > **Arifa SMS**.
Ignition 8.3: Services > Alarming > Notification > **Create Profile +** > **Arifa SMS**.

| Setting | Value |
| --- | --- |
| Username | The Africa's Talking app **username**. It is not the app's display name |
| API Key | The app's key. On 8.1 it appears as two boxes labelled **Password**: type the key in both. On 8.3 choose **Embedded**, then type the key |
| Live | **Off** to use Africa's Talking's free simulator. On only for real SMS, which cost money |
| Sender ID | The registered sender name, or the shortcode when you use replies. Blank uses Africa's Talking's default sender |
| Maximum SMS per Hour | A cost cap. 100 by default. 0 means no limit |
| Allow Acknowledgement by SMS Reply | On only when you have a shortcode, and then set the Reply Keyword |

Every setting is explained in [settings-reference.md](settings-reference.md).

## 5. Connect it to an alarm

1. Give each on-call user a contact of type **SMS**, written as `07...`, `01...` or `+254...`.
2. Create an on-call roster with those users.
3. In the Designer, add a Notification block to your alarm pipeline: profile **Arifa SMS**, your roster, and
   **Test Mode = Yes** for the first test.
4. Trigger an alarm. In the gateway log, search `Arifa`. You will see a block starting
   `THIS PROFILE IS RUNNING IN TEST MODE`, with the message that would be sent and a masked phone number.
5. Set Test Mode to **No** and trigger again. With Live off, the message appears in Africa's Talking's simulator.
   With Live on, it goes to the real phone.

## 6. What to check

- The profile status on the notification profiles page shows **Running**. After a failed send it shows
  `Last send failed`, and after the SMS cap is reached it shows `SMS limit reached`.
- Each real send logs its cost, for example `SMS accepted for 1 recipient (cost KES 0.80)`.
- A line starting `UNEXPECTED COST` means Africa's Talking billed in an unexpected currency.
- A user with no SMS contact is skipped by Ignition, with no error. Check every roster has valid numbers.
- SMS is best effort. Delivery to the phone is not guaranteed.

## Known limits

- SMS is best effort. Arifa reports "accepted by Africa's Talking", not "delivered to the phone".
- Reply codes are kept in memory, so after a gateway restart or a failover, replies to earlier messages are ignored.
- Africa's Talking is the only provider today. Ignition 8.1 (8.1.33 or later) and 8.3 (8.3.3 or later) are supported.
- Replies were tested against Africa's Talking's sandbox. They have not been tested on a live shortcode.
- Tested with Kenyan numbers. For another country, set Default Country Code and Expected Cost Currency on the profile.
- A profile saved on an 8.1 gateway is not carried over when the gateway is upgraded to 8.3. Install the 8.3 module and
  create the profile again.
- How the API key is protected differs between 8.1 and 8.3. See [SECURITY.md](../SECURITY.md).

## Stopping it

- Stop all real SMS at once: untick **Enabled** on the profile, or set Test Mode to Yes on the pipeline block.
- Return to the free simulator: untick **Live**.
- Remove the module: uninstall it from the Modules page.
