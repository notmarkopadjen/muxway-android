# Security policy

muxway gives remote access to terminal sessions. A flaw in it can hand an
attacker a shell, so security reports come before everything else.

## Reporting a vulnerability

Do not open a public issue, and do not describe the flaw in a pull
request. Report it through GitHub's private vulnerability reporting:
the repository's **Security** tab, *Report a vulnerability*. Only the
maintainers see it, the thread stays private until the fix is out, and
a CVE can be issued from it. There is no e-mail address for reports.

Say what you found, how to reproduce it (a request, a pane name, a
screen), what it gives an attacker, and which commit or release you
tested. A proof of concept against your own hub and host is welcome;
never against someone else's.

## What to expect

| Step | Within |
|---|---|
| Acknowledgement | 3 days |
| First assessment and severity | 7 days |
| Fix or mitigation for a high or critical finding | 30 days |
| Public disclosure | after the fix is released, or 90 days after the report, whichever comes first, agreed with you |

This is a volunteer project; the days are targets, not a contract. Credit
goes in the release notes and the advisory if you want it; say so in the
report.

## Supported versions

| Version | Supported |
|---|---|
| `main` | yes |
| the latest release | yes |
| anything older | no: upgrade first |

## Scope

The Android app, the Wear OS app and the watch face in this repository.
Reports are most welcome on:

- how the token is stored (the Android Keystore) and when it is shown,
  logged or sent;
- cleartext or wrongly trusted connections to a hub, certificate handling;
- what a shared file, a pasted image or an intent from another app can do;
- what a hostile pane's content can do to the app (injection, crashes,
  spoofed names or states);
- what the watch shows on the wrist and sends from it;
- notifications that leak pane content.

The hub, the agent and the protocol are in
[muxway](https://github.com/notmarkopadjen/muxway); report flaws there to
that repository.

Out of scope: a device that is already rooted or has a hostile keyboard,
denial of service against your own hub, and social engineering.

## Safe harbour

Research done in good faith against your own installation, within this
policy, is authorised as far as the maintainers can authorise it: they
will not report it to anyone or take legal action over it. Testing
against an installation you do not own is not covered; nor can the
maintainers speak for third parties such as a hosting provider.
