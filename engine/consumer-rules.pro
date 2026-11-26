# NativeTank's native methods are looked up by name from libdrifttank.so.
-keepclasseswithmembernames class com.jpcottin.drifttank.engine.NativeTank {
    native <methods>;
}
