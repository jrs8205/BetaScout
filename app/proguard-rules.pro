# DataStore Preferences serializes via its bundled protobuf-lite, which looks up
# message fields reflectively by their original names; letting R8 rename them
# crashes every preference write (RuntimeException: Field value_ ... not found).
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite {
    <fields>;
}
