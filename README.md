# CreatorBucket for Android

CreatorBucket collects phone footage into named project folders without changing the way you record.

## Use it

1. Record videos using your regular Camera app.
2. In Gallery or Google Photos, select one or more videos and tap **Share**.
3. Choose **CreatorBucket**, select or create a bucket, and wait for the full-resolution copies to finish.
4. Open the bucket to share its clips to an editor or copy the whole project to an SSD.

CreatorBucket keeps its working footage in private app storage, outside Gallery indexing, so project copies do not appear as duplicate videos in your camera roll. Copy is the default import behavior.

Use **Select** inside a bucket to choose one or more project copies and delete them. CreatorBucket asks for confirmation and leaves the original camera videos alone. Tap any clip to preview it in the phone's video player.

Use **Done with this project** only after sharing or exporting it. It asks for confirmation, then deletes the project copies while leaving original camera videos alone.

The default home view is **Footage timeline**. Switch to **Dashboard tiles** or **Project gallery** from the gear menu. Export creates numbered names for clips that already exist in the destination, so it never overwrites a prior export.

To move a local camera video, choose **Move after copy** from the gear menu, select it from the phone's Gallery or Files app, then share it to CreatorBucket. Android displays its own confirmation prompt before the original is deleted; approve that prompt to finish the move. Google Photos, Drive, and other cloud sources do not grant CreatorBucket permission to delete their originals, so those clips are copied and retained there.

Existing CreatorBucket copies from the earlier public folder are migrated out of Gallery the next time the app opens.

## Build and install

```sh
./gradlew :app:assembleDebug
android run --apks app/build/outputs/apk/debug/app-debug.apk --device <device-id>
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Current scope

- Receives single and multiple `video/*` items from the Android Share sheet.
- Stores bucket names and clip records in an on-device SQLite database.
- Imports original-quality video copies into hidden app storage.
- Shares a bucket's clips to an editing app.
- Uses Android's folder picker to copy a bucket to a connected SSD or another selected folder.
- Includes Copy/Move import preferences and Clean, Creative, and Dark themes.
- Lets you keep a note or shot list inside each bucket.
- Shows project, clip, and footage-duration totals on the home screen.

Photos, cloud backup, and background imports are not in this first build. App-private working copies are removed if CreatorBucket is uninstalled, so export active projects before uninstalling.
