# The stream server

Live Indian TV plays Jio's channels through [JioTV Go](https://github.com/JioTV-Go/jiotv_go) by Mohammed Rabil
([rabilrbl](https://github.com/rabilrbl)) and contributors, licensed under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).

It is compiled, unchanged, for 32-bit ARM Android and shipped inside the Live Indian TV APK as
`lib/armeabi-v7a/libjiotvgo.so`, which Android unpacks so the app can run it. Live Indian TV starts it on `127.0.0.1` only
(nothing outside the TV can reach it), with its home folder in Live Indian TV's private storage, where the Jio sign-in is
kept, and with its log file switched off (`JIOTV_LOG_PATH=/dev/null`), because that log can contain sign-in tokens.

The binary is not in git. `server/build-jiotv-go.sh` builds it from source at a pinned tag; the releases on GitHub
contain the one built for 1.0.0 (JioTV Go v3.22.0).

Changes made to JioTV Go: none to its code. It is built for `android/arm` and renamed to `libjiotvgo.so`.
