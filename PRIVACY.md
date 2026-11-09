# Wren privacy policy

Last updated: 7 November 2026

Wren is an Android web browser made by AndroidPoet. It has no account, no analytics, no ads and
no servers of its own. Nothing about you or your browsing is sent to us, because there is nowhere
for it to go.

## What stays on your phone

Your history, bookmarks, open tabs, settings, downloads, site icons and filter lists are stored
only on your phone, in Wren's private storage. Wren turns off Android's app backup, so none of it
is copied to a cloud backup. Private tabs write no history, cookies or cache at all.

**Clear everything** in the menu closes every tab and deletes your history, cookies and cache.
**Clear on exit** in Settings wipes cookies and cache whenever you close Wren. Bookmarks and
settings stay until you delete them yourself. Uninstalling Wren deletes all of it.

## What leaves your phone

Wren only makes these connections:

| Connection | Goes to | When |
| --- | --- | --- |
| The pages you open | The sites you visit | Always |
| Search suggestions | The search engine you chose (DuckDuckGo by default) | Only if turned on in Settings, then while you type |
| Filter-list updates | The filter-list hosts, such as easylist.to | When a list is over a week old, or when you tap Update now |
| Site icons for the home page | The site the icon belongs to | Unless turned off in Settings |
| Safe Browsing checks | Google, through Android's WebView; only short hashed prefixes of addresses are sent | Always, except in Tor mode |
| Passkey sign-ins | Your phone's password manager | Only when a site asks you to sign in with a passkey; off in Tor mode |
| Downloads | The site hosting the file; through Tor in Tor mode | When you download a file |
| Translate page | Google Translate | Only when you choose Translate page from the menu |
| Tor | The Tor network | Only if you turn Tor mode on |

The sites you visit see your connection as with any browser, and their own privacy policies
apply. Wren blocks known trackers and ads on them, declines cookie banners where it can, and by
default exposes the Do Not Track and Global Privacy Control signals to their scripts (the
matching request headers are sent only where the engine lets an app add them).

## What Wren does not protect against

Wren runs on Android's system WebView, which is the Chromium engine shipped and updated by Google
or your phone maker. The engine's version, security patches and internal isolation are outside
Wren's control. Tor mode routes all of Wren's traffic through Tor, keeps Tor pages in their own
storage profile that is deleted when Tor is turned off or a new identity is requested, records
no history for them, gives each host its own circuit, runs downloads through Tor, removes
WebRTC, shows pages a UTC clock and answers in English, and can reach Tor through obfs4,
Snowflake or meek bridges where Tor is blocked (Snowflake and meek look up their front domain
through the phone's DNS first, as Tor Browser does), but it does not make you
indistinguishable from other users the way Tor Browser does: fingerprint noise is stable per
site for the session rather than the same for every user. One lookup escapes it: a page's DNS prefetch hint is resolved by the engine through
the phone's DNS rather than through Tor, and no app can turn that off; Tor over a VPN or a
private DNS server moves that disclosure away from your ISP. Web Workers do not get the clock and
WebRTC shims. The engine adds an `X-Requested-With` header naming
Wren to the requests pages make, which no app can switch off yet; Wren removes it from the page
request itself and sends Chrome's reduced user agent, so sites learn the browser but not the
phone. Wren has not been independently audited. If your safety depends on anonymity, use Tor
Browser for Android.

## Permissions

- **Internet and network state**: to load pages and know when you are offline.
- **Notifications** and a **foreground service**: to show download progress and keep a download
  that runs through Tor going while Wren is in the background.
- **Storage** (Android 9 and older only): to save downloads to your Downloads folder.

Wren does not request access to your location, camera, microphone, contacts or files, and it
refuses those requests from websites.

## Children

Wren collects no personal information from anyone, including children.

## Changes

Changes to this policy are published in this file, and its history is public on GitHub.

## Contact

Open an issue at <https://github.com/AndroidPoet/Wren/issues>, or report a security or privacy
problem privately as described in [SECURITY.md](SECURITY.md).
