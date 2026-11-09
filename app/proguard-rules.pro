# Rules untuk GoMobile AAR
-keep class go.** { *; }
-keep class core.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
