# xposed-api/ — Xposed API for compilation

**No stubs live here: the real official API jar is vendored instead.**

The hook sources compile against the official Xposed API v82 artifact
`de.robv.android.xposed:api:82` (the standard `compileOnly` dependency for
LSPosed modules):

```
app/libs/xposed-api-82.jar
size   : 25478 bytes
sha256 : f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25
classes: IXposedHookLoadPackage, IXposedHookZygoteInit,
         IXposedHookInitPackageResources, XposedBridge, XposedHelpers,
         XC_MethodHook (+ MethodHookParam), XC_MethodReplacement,
         callbacks/XC_LoadPackage$LoadPackageParam, callbacks/XCallback, ...
```

It is used **compileOnly**: `tools/build.sh` puts it on `javac -classpath`
only and `d8` never sees it, so the API classes are not packaged into
`dist/GrabNetPrinter.apk` (LSPosed provides them at runtime). `tools/verify.sh`
asserts that no `de/robv/android/xposed/` classes appear in the APK.

Why not handwritten stubs: the real jar is authoritative for signatures.
In particular `XposedBridge.hookAllMethods(...)` and `hookAllConstructors(...)`
return `Set<XC_MethodHook.Unhook>` (not `void`), and `XposedHelpers.*` has the
full v82 overload set; usage as a statement still compiles, and helpers the
hook code may need later (`findAndHookConstructor`, `getAdditionalInstanceField`,
`callMethod` with explicit parameter types, ...) are present for free.

The jar is checked in, so the build works fully offline. If it is ever
deleted, restore it with:

```sh
tools/fetch-api.sh          # verifies an existing jar against the SHA-256
tools/fetch-api.sh --force  # re-download from the Maven mirrors
```

`https://api.xposed.info/api-82.jar` currently 404s; the script uses the
Aliyun Maven mirror of the same artifact and verifies the SHA-256 and the
expected class list. See `tools/README.md` for the rest of the toolchain.
