// Root build script (主 Agent 独占).
// All plugins are applied via buildSrc convention plugins (bess-android-*),
// which already put AGP/KSP/Hilt/Compose on the classpath. No root-level
// `apply false` plugin declarations to avoid "already on classpath" conflicts.
