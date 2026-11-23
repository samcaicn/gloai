# Backup & data (Settings > Backup & data)

Export everything to a plain ZIP file, or restore from one. The ZIP is not encrypted
on purpose: it is a simple file the user hands to the new install.

## Why a backup is needed

Secrets are AES-GCM encrypted with AndroidKeyStore keys, and uninstall invalidates
those keys, so raw files cannot be carried to a differently signed install. Android
Auto Backup is off for this app, and the private data directory cannot be pulled
from a non-rooted phone. Export only works while the old install is still installed:
only it holds the KeyStore keys that can decrypt the data.

## What it does

`BackupEngine` walks the app's files directory and SharedPreferences, decrypts
every encrypted file with its own KeyStore key, and packs the plaintext into one
ZIP with a `manifest.json` (backup format version 1). Import validates the whole
ZIP first (manifest, version, entry list, paths), then re-encrypts every secret
under the fresh install's KeyStore keys.

Included: providers and keys, chats, personas, skills, memory, workflows, saved
checklists, task runs, proactive data, and settings (including web search/fetch,
voice/speech, calls, SMS login consent, and device directory settings).
Not included: wireless-debugging pairing (re-pair after import), the accessibility
service setting (turn it back on in Android Settings), the sandbox workspace, and
file library attachments. The sandbox workspace and library attachments are
deliberately left out because they can be very large.

The ZIP contains all secrets in plaintext. The screen warns the user to keep it
private, not share it, and delete it after importing. Nothing is uploaded anywhere.

## After an import

Providers, chats and memory are loaded once at startup and cached in memory, so
the restored data only takes effect after a restart. The import dialog offers to
close the app; the user reopens it, re-pairs wireless debugging, and turns the
accessibility service back on.

## Code

- `app/src/main/java/com/wangzan101/mika/backup/BackupEngine.kt`: the ZIP
  engine, the encrypted-file registry, and the manifest. Pure JVM, no Android
  APIs, so unit tests run it with a fake `KeyCipher`.
- `app/src/main/java/com/wangzan101/mika/backup/BackupPrefs.kt`: the
  SharedPreferences values that are stored as base64 ciphertext, and the XML
  transform that decrypts them on export and re-encrypts them on import.
- `app/src/main/java/com/wangzan101/mika/backup/ui/BackupScreen.kt`: the Settings
  screen (export via the system file picker, import with a Replace all
  confirmation when data already exists).
- `app/src/test/java/com/wangzan101/mika/backup/BackupEngineTest.kt`:
  round-trip with two different keys, corrupt ZIP, unknown future format
  version, and import over existing data.
