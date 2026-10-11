# Fixed release signing

Release identity: `7de8ec4d38a6c7854981b73fe34161c17d97fd93555358bd11f46fecde4ab878`
The 4096-bit RSA keystore is private and never committed. Version 0.1.9 uses
versionCode 10 and a non-debuggable release build. Keep the same private key for
all subsequent versions and increment versionCode; then Android install -r keeps data.
Earlier APKs used disposable CI debug keys. Their keys were not saved, so they
require one migration uninstall/reinstall. Export routes and save URLs beforehand.

Set one repository Actions secret `TVATLAS_SIGNING_BUNDLE` using the owner's
private bundle file. It contains base64 keystore, alias, store_password and
key_password. `scripts/prepare_signing.py` restores it to runner temporary storage,
verifies its public certificate, and writes private Gradle signing properties.
No credentials are printed. Temporary signing files are deleted after the build.
An absent secret produces ONLY a signing-input artifact; a wrong key fails the job.
No release APK is published or offered by the app without the pinned signature.

Local builds use the same private properties file via TVATLAS_SIGNING_PROPERTIES.
Back up the keystore and secret bundle privately; never put them in public GitHub.

CI runs Android UI tests, then builds a newer test versionCode and performs
`adb install -r` with the same test signer. Instrumentation verifies a private file
and selected-node preference survived. CI debug test signing is independent of the
private release signer; release certificate identity is verified separately.
