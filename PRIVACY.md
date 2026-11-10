# Wren privacy policy

Last updated: 10 November 2026

Wren is an Android web browser made by AndroidPoet. It has no account, no analytics, no ads and
no servers of its own. Nothing about you or your browsing is sent to us, because there is nowhere
for it to go.

## What stays on your phone

Your history, bookmarks, open tabs, settings, downloads, site icons and filter lists are stored
only on your phone, in Wren's private storage. Wren turns off Android's app backup, so none of it
is copied to a cloud backup. Private tabs write no history, cookies or cache at all, pages in
them cannot use WebRTC, the motion sensors or list your cameras and microphones, and the
keyboard is asked not to learn from what you type in them or in the address bar.

**Clear everything** in the menu closes every tab and deletes your history, cookies and cache.
**Clear on exit** in Settings wipes cookies and cache whenever you close Wren. Bookmarks and
settings stay until you delete them yourself. Uninstalling Wren deletes all of it.

## What leaves your phone

Wren only makes these connections:

| Connection | Goes to | When |
| --- | --- | --- |
| The pages you open | The sites you visit | Always |
| Search suggestions | The search engine you chose (DuckDuckGo by default) | Only if turned on in Settings, then while you type |
| Filter-list updates | The filter-list hosts: easylist.to, secure.fanboy.co.nz, raw.githubusercontent.com and, for the regional lists you turn on, easylist-downloads.adblockplus.org | On the first launch (the bundled lists are replaced by current ones), when a list is over a week old, or when you tap Update now |
| Site icons for the home page | The site the icon belongs to | Unless turned off in Settings |
| Safe Browsing checks | Google, through Android's WebView; only short hashed prefixes of addresses are sent | Unless turned off in Settings; always off in Tor mode |
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
no history for them, keeps their disk cache at the smallest size the engine allows, gives each
host its own circuit, runs downloads through Tor, removes
WebRTC and the motion sensors, forces third-party cookies off, shows pages a UTC clock with
100 ms precision and answers in English, letterboxes pages to 100 px steps so the viewport does
not name the phone, asks the keyboard not to learn from what is typed, offers a Safest level
with JavaScript off and web fonts blocked, and can reach Tor through obfs4,
Snowflake or meek bridges where Tor is blocked (before Tor is reached, meek talks to a content
delivery front over your normal connection, and Snowflake talks to its front, to a STUN server
named in the bridge line and to a volunteer's WebRTC proxy, as Tor Browser does), but it does not make you
indistinguishable from other users the way Tor Browser does: the canvas is blank under Tor as
in Tor Browser, but the fonts stay visible and audio and WebGL carry per-site noise, stable for
the session and separate for normal, private and Tor pages, rather than the same answer for
every user. Turning Tor on closes the open pages instead of reloading them over Tor; a new
identity deletes the Tor profile and leaves the clear-net one alone. One lookup escapes it: a page's DNS prefetch hint is resolved by the engine through
the phone's DNS rather than through Tor, and no app can turn that off; Tor over a VPN or a
private DNS server moves that disclosure away from your ISP. Web Workers get the clock, canvas and
locale shims through a bootstrap; WebRTC is not reachable from a worker. The engine adds an `X-Requested-With` header naming
Wren to the requests pages make, which no app can switch off; Wren removes it from the page
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

On Android TV the voice search button starts the TV's own speech recogniser, a separate app with
its own microphone permission and its own privacy policy. What you say goes to that app, not to
Wren and not through Tor; Wren receives the recognised text and loads it as a search or an
address. The button is absent in Tor mode and in private tabs.

## Children

Wren collects no personal information from anyone, including children.

## Changes

Changes to this policy are published in this file, and its history is public on GitHub.

## Contact

Open an issue at <https://github.com/AndroidPoet/Wren/issues>, or report a security or privacy
problem privately as described in [SECURITY.md](SECURITY.md).
