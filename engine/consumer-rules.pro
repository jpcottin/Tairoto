# NativeTank's native methods are looked up by name from libtairoto.so.
-keepclasseswithmembernames class com.jpcottin.tairoto.engine.NativeTank {
    native <methods>;
}
