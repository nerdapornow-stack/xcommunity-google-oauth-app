# Xcommunity Google Sign-in

<img src="ic_launcher-playstore.png" width="96" alt="Xcommunity">

Android app that signs in with Google for [Xcommunity](https://github.com/nerdapornow-stack/xcommunity). Google Identity Services refuses embedded WebViews, so the app loads the sign-in page in a private GeckoView session with the bundled [Xcommunity Google OAuth WebExtension](https://github.com/nerdapornow-stack/xcommunity-google-oauth-webextension).

## Install

Download `xcommunity-google-signin.apk` from [Releases](https://github.com/nerdapornow-stack/xcommunity-google-oauth-app/releases) and install it (arm64 devices, Android 8+).

## Use

1. Open **Xcommunity Google Sign-in** and complete Google's sign-in.
2. Tap **Copy token** and paste it into Xcommunity's sign-in screen (Google tab). The token expires in about an hour.

If the Xcommunity app starts this app for a result (`com.xcommunity.app.google_oauth.action.REQUEST_TOKEN`), the token goes straight back to it. That only happens when both apps are signed with the same key; otherwise the token is shown for copying.

## Build

```
git clone --recurse-submodules https://github.com/nerdapornow-stack/xcommunity-google-oauth-app.git
cd xcommunity-google-oauth-app && ./gradlew assembleRelease
```

Set `ANDROID_KEYSTORE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` to sign with your own key (CI reads the base64 keystore from the `ANDROID_KEYSTORE_BASE64` secret). Without them the APK is signed with the debug key.

## Credits

Based on Open Grind's and Free Grind's Google OAuth companion apps. MIT licensed.
