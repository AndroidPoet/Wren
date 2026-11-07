# Wren privacy policy

Last updated: 4 November 2026

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
| Tor | The Tor network | Only if you turn Tor mode on |

The sites you visit see your connection as with any browser, and their own privacy policies
apply. Wren blocks known trackers and ads on them, declines cookie banners where it can, and by default
sends the Do Not Track and Global Privacy Control signals.

## Permissions

- **Internet and network state**: to load pages and know when you are offline.
- **Notifications**: to show download progress.
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
